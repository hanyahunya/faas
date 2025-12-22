package com.hanyahunya.invoker.application.service;

import com.hanyahunya.invoker.application.port.in.InvokeUseCase;
import com.hanyahunya.invoker.application.port.out.AgentInvokePort;
import com.hanyahunya.invoker.application.port.out.ContainerPoolPort;
import com.hanyahunya.invoker.application.port.out.FunctionAuthPort;
import com.hanyahunya.invoker.common.exception.BusinessException;
import com.hanyahunya.invoker.domain.error.FunctionErrorCode;
import com.hanyahunya.invoker.common.exception.PermissionDeniedException;
import com.hanyahunya.invoker.domain.model.ContainerInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvokeService implements InvokeUseCase {

    private final FunctionAuthPort functionAuthPort;
    private final ContainerPoolPort containerPoolPort;
    private final AgentInvokePort agentInvokePort;

    @Override
    public Result invoke(Command command) {
        UUID functionId = command.functionId();
        log.info("Invoke requested by Function: [{}]", functionId);

        // 권한 검증 및 S3 Key 획득
        FunctionAuthPort.Result authResult = functionAuthPort.authenticateFunction(
                functionId,
                command.accessKey()
        );

        if (!authResult.isValid()) {
            log.warn("Access denied Function: [{}]", functionId);
            throw new PermissionDeniedException(FunctionErrorCode.INVOKE_PERMISSION_DENIED);
        }

        // 컨테이너 확보
        Optional<ContainerInfo> optionalContainerInfo = containerPoolPort.popContainer(functionId);
        ContainerInfo containerInfo;

        if (optionalContainerInfo.isPresent()) {
            containerInfo = optionalContainerInfo.get();
            log.info("Warm container found: {}", containerInfo);
        } else {
            // Cold Start
            String s3Key = authResult.s3Key();
            log.info("No idle container. Initiating Cold Start for [{}] (S3: {})", functionId, s3Key);

            // s3Key 전달
            containerPoolPort.requestContainerCreation(functionId, s3Key);

            containerInfo = containerPoolPort.waitContainer(functionId)
                    .orElseThrow(() -> {
                        log.warn("Container initialization timed out for Function: [{}]", functionId);
                        return new BusinessException(FunctionErrorCode.CONTAINER_INIT_TIMEOUT);
                    });
            log.info("Cold start completed. Container acquired: {}", containerInfo);
        }

        // Agent 실행 요청
        AgentInvokePort.AgentResponse response = agentInvokePort.executeFunction(
                containerInfo.agentIp(),
                containerInfo.sockPath(),
                command.params()
        );

        // 결과 처리
        if (response.success()) {
            log.info("Execution Success. Mem: {} bytes", response.memoryUsage());
            containerPoolPort.returnContainer(functionId, containerInfo);
            return new Result(response.result());
        } else {
            log.error("Execution Failed: {}", response.errorMessage());
            throw new RuntimeException("Function execution failed: " + response.errorMessage());
        }
    }
}