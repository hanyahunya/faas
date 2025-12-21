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

        // 권한 검증
        boolean isAuthenticated = functionAuthPort.authenticateFunction(
                functionId,
                command.accessKey()
        );

        if (!isAuthenticated) {
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
            log.info("No idle container. Initiating Cold Start for [{}]", functionId);
            containerPoolPort.requestContainerCreation(functionId);

            containerInfo = containerPoolPort.waitContainer(functionId)
                    .orElseThrow(() -> {
                        log.warn("Container initialization timed out for Function: [{}]", functionId);
                        return new BusinessException(FunctionErrorCode.CONTAINER_INIT_TIMEOUT);
                    });
            log.info("Cold start completed. Container acquired: {}", containerInfo);
        }

        // Agent 실행요청
        AgentInvokePort.AgentResponse response = agentInvokePort.executeFunction(
                containerInfo.agentIp(),
                containerInfo.sockPath(),
                command.params()
        );

        // 실행결과 처리 및 컨테이너 반환
        if (response.success()) {
            log.info("Execution Success. Mem: {} bytes", response.memoryUsage());

            // 성공 시 RPUSH
            containerPoolPort.returnContainer(functionId, containerInfo);

            return new Result(response.result());
        } else {
            log.error("Execution Failed: {}", response.errorMessage());
            // 실패 시 정책에 따라 컨테이너를 버리거나 재검사 로직 필요 (여기서는 반환 안함)
            throw new RuntimeException("Function execution failed: " + response.errorMessage());
        }
    }
}