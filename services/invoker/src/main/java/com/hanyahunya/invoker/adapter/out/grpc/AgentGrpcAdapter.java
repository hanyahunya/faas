package com.hanyahunya.invoker.adapter.out.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyahunya.grpc.AgentServiceGrpc;
import com.hanyahunya.grpc.ExecuteRequest;
import com.hanyahunya.grpc.ExecuteResponse;
import com.hanyahunya.invoker.application.port.out.AgentInvokePort;
import com.hanyahunya.invoker.infra.grpc.AgentChannelManager;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGrpcAdapter implements AgentInvokePort {

    private final ObjectMapper objectMapper;
    private final AgentChannelManager channelManager;

    @Override
    public AgentResponse executeFunction(String agentIp, String sockPath, Map<String, Object> params) {
        // 매니저에 채널요청
        ManagedChannel channel = channelManager.getChannel(agentIp);

        try {
            AgentServiceGrpc.AgentServiceBlockingStub stub = AgentServiceGrpc.newBlockingStub(channel);

            String payloadJson = objectMapper.writeValueAsString(params);

            ExecuteRequest request = ExecuteRequest.newBuilder()
                    .setSockPath(sockPath)
                    .setInputPayload(payloadJson)
                    .build();

            ExecuteResponse response = stub.execute(request);

            return new AgentResponse(
                    response.getResult(),
                    response.getSuccess(),
                    response.getMemoryUsage(),
                    response.getErrorMessage()
            );

        } catch (StatusRuntimeException e) {
            // todo [장애 처리] gRPC 통신 에러 (연결 끊김, 타임아웃 등)
            log.error("gRPC Communication Error with Agent [{}]: {}", agentIp, e.getStatus());
            return new AgentResponse(null, false, 0, "Agent Network Error: " + e.getMessage());

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Parameter serialization failed", e);
        }
    }
}