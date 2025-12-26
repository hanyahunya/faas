package com.hanyahunya.invoker.application.service;

import com.hanyahunya.invoker.application.port.in.InvokeUseCase;
import com.hanyahunya.invoker.application.port.out.*;
import com.hanyahunya.invoker.common.exception.BusinessException;
import com.hanyahunya.invoker.domain.error.FunctionErrorCode;
import com.hanyahunya.invoker.common.exception.PermissionDeniedException;
import com.hanyahunya.invoker.domain.model.ContainerInfo;
import com.hanyahunya.invoker.domain.model.ExecutionLog;
import com.hanyahunya.invoker.domain.model.ExecutionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvokeService implements InvokeUseCase {

    private final FunctionAuthPort functionAuthPort;
    private final ContainerPoolPort containerPoolPort;
    private final AgentInvokePort agentInvokePort;
    private final ExecutionLogPort executionLogPort;

    // [변경] I/O 블로킹 없는 처리를 위해 가상 스레드 익스큐터 사용 (Java 21+)
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Result invoke(Command command) {
        long startProcessingTime = System.currentTimeMillis();
        LocalDateTime requestTime = LocalDateTime.now();

        UUID functionId = command.functionId();
        UUID requestId = UUID.randomUUID();
        log.debug("Invoke requested. [ReqId: {}, FuncId: {}]", requestId, functionId);

        // 1. 권한 검증
        FunctionAuthPort.Result authResult = functionAuthPort.authenticateFunction(
                functionId,
                command.accessKey()
        );

        if (!authResult.isValid()) {
            throw new PermissionDeniedException(FunctionErrorCode.INVOKE_PERMISSION_DENIED);
        }

        // 2. 컨테이너 확보
        ContainerInfo containerInfo;
        ExecutionType executionType;
        long coldStartDuration = 0;

        Optional<ContainerInfo> optionalContainerInfo = containerPoolPort.popContainer(functionId);

        if (optionalContainerInfo.isPresent()) {
            executionType = ExecutionType.WARM;
            containerInfo = optionalContainerInfo.get();
        } else {
            executionType = ExecutionType.COLD;
            String s3Key = authResult.s3Key();
            log.debug("Cold Start initiated for [{}]", functionId);

            long coldStartStart = System.currentTimeMillis();
            containerPoolPort.requestContainerCreation(functionId, s3Key);

            containerInfo = containerPoolPort.waitContainer(functionId)
                    .orElseThrow(() -> {
                        log.debug("Container timeout: [{}]", functionId);
                        return new BusinessException(FunctionErrorCode.CONTAINER_INIT_TIMEOUT);
                    });

            coldStartDuration = System.currentTimeMillis() - coldStartStart;
        }

        // 3. Agent 실행 요청 (여기까지는 동기)
        AgentInvokePort.AgentResponse response = agentInvokePort.executeFunction(
                containerInfo.agentIp(),
                containerInfo.sockPath(),
                command.params(),
                requestId
        );

        long totalDuration = System.currentTimeMillis() - startProcessingTime;

        // -------------------------------------------------------------------------
        // [핵심 변경] "Redis 반납" -> "DB 저장" 순서로 백그라운드 처리 (Fire-and-Forget)
        // -------------------------------------------------------------------------

        // 람다 내부에서 사용하기 위해 final 변수 할당
        final ContainerInfo finalContainerInfo = containerInfo;
        final long finalColdStartDuration = coldStartDuration;

        asyncExecutor.submit(() -> {
            try {
                // [1순위] Redis에 컨테이너 반납 (가장 중요: 다른 요청이 바로 쓸 수 있도록)
                if (response.success()) {
                    containerPoolPort.returnContainer(functionId, finalContainerInfo);
                }

                // [2순위] 실행 로그 저장 (비동기)
                ExecutionLog executionLog = ExecutionLog.builder()
                        .id(requestId)
                        .functionId(functionId)
                        .requestStartTime(requestTime)
                        .success(response.success())
                        .memoryUsage(response.memoryUsage())
                        .durationMs(response.durationMs())
                        .totalProcessingTimeMs(totalDuration)
                        .logS3Key(response.logS3Key())
                        .executionType(executionType)
                        .coldStartDurationMs(finalColdStartDuration)
                        .build();

                executionLogPort.saveLog(executionLog);

            } catch (Exception e) {
                // 백그라운드 작업 실패는 메인 로직(응답)에 영향 주지 않음, 로그만 남김
                log.error("Async cleanup failed [ReqId: {}]: {}", requestId, e.getMessage());
            }
        });

        // 4. 즉시 결과 반환 (사용자 대기 시간 최소화)
        if (response.success()) {
            return new Result(response.result());
        } else {
            log.error("Execution Failed: {}", response.errorMessage());
            throw new RuntimeException("Function execution failed: " + response.errorMessage());
        }
    }
}