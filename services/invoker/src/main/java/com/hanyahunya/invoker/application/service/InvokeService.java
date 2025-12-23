// invoker/application/service/InvokeService.java

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

@Slf4j
@Service
@RequiredArgsConstructor
public class InvokeService implements InvokeUseCase {

    private final FunctionAuthPort functionAuthPort;
    private final ContainerPoolPort containerPoolPort;
    private final AgentInvokePort agentInvokePort;
    private final ExecutionLogPort executionLogPort; // [추가] 로그 저장 포트

    @Override
    public Result invoke(Command command) {
        long startProcessingTime = System.currentTimeMillis(); // 전체 처리 시간 측정 시작
        LocalDateTime requestTime = LocalDateTime.now();

        UUID functionId = command.functionId();
        UUID requestId = UUID.randomUUID();
        log.info("Invoke requested. [ReqId: {}, FuncId: {}]", requestId, functionId);

        // 1. 권한 검증
        FunctionAuthPort.Result authResult = functionAuthPort.authenticateFunction(
                functionId,
                command.accessKey()
        );

        if (!authResult.isValid()) {
            throw new PermissionDeniedException(FunctionErrorCode.INVOKE_PERMISSION_DENIED);
        }

        // 2. 컨테이너 확보 및 Cold/Warm 판단
        ContainerInfo containerInfo;
        ExecutionType executionType;
        long coldStartDuration = 0;

        Optional<ContainerInfo> optionalContainerInfo = containerPoolPort.popContainer(functionId);

        if (optionalContainerInfo.isPresent()) {
            // WARM START
            executionType = ExecutionType.WARM;
            containerInfo = optionalContainerInfo.get();
            log.info("Warm container found: {}", containerInfo);
        } else {
            // COLD START
            executionType = ExecutionType.COLD;
            String s3Key = authResult.s3Key();
            log.info("No idle container. Initiating Cold Start for [{}]", functionId);

            long coldStartStart = System.currentTimeMillis(); // Cold Start 시작

            containerPoolPort.requestContainerCreation(functionId, s3Key);

            containerInfo = containerPoolPort.waitContainer(functionId)
                    .orElseThrow(() -> {
                        log.warn("Container initialization timed out for Function: [{}]", functionId);
                        return new BusinessException(FunctionErrorCode.CONTAINER_INIT_TIMEOUT);
                    });

            coldStartDuration = System.currentTimeMillis() - coldStartStart; // Cold Start 소요 시간
            log.info("Cold start completed in {}ms. Container acquired: {}", coldStartDuration, containerInfo);
        }

        // 3. Agent 실행 요청
        AgentInvokePort.AgentResponse response = agentInvokePort.executeFunction(
                containerInfo.agentIp(),
                containerInfo.sockPath(),
                command.params(),
                requestId
        );

        long totalDuration = System.currentTimeMillis() - startProcessingTime; // 전체 처리 시간

        // 4. 실행 로그 비동기 저장 (Fire-and-forget 방식 추천하나 여기선 동기 호출로 구현)
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
                .coldStartDurationMs(coldStartDuration)
                .build();

        try {
            executionLogPort.saveLog(executionLog);
        } catch (Exception e) {
            log.error("Failed to save execution log", e);
            // 로그 저장이 실패해도 함수 실행 결과는 반환해야 함
        }

        // 5. 결과 처리
        if (response.success()) {
            containerPoolPort.returnContainer(functionId, containerInfo);
            return new Result(response.result());
        } else {
            log.error("Execution Failed: {}", response.errorMessage());
            throw new RuntimeException("Function execution failed: " + response.errorMessage());
        }
    }
}