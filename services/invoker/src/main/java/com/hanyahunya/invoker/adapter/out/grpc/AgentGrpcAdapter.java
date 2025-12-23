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
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGrpcAdapter implements AgentInvokePort {

    private final ObjectMapper objectMapper;
    private final AgentChannelManager channelManager;

    @Override
    public AgentResponse executeFunction(String agentIp, String sockPath, Map<String, Object> params, UUID requestId) {
        ManagedChannel channel = channelManager.getChannel(agentIp);

        try {
            AgentServiceGrpc.AgentServiceBlockingStub stub = AgentServiceGrpc.newBlockingStub(channel);
            String payloadJson = objectMapper.writeValueAsString(params);

            ExecuteRequest request = ExecuteRequest.newBuilder()
                    .setSockPath(sockPath)
                    .setInputPayload(payloadJson)
                    .setRequestId(requestId.toString())
                    .build();

            ExecuteResponse response = stub.execute(request);

            return new AgentResponse(
                    response.getResult(),
                    response.getSuccess(),
                    response.getMemoryUsage(),
                    response.getErrorMessage(),
                    response.getDurationMs(),
                    response.getLogS3Key()
            );

        } catch (StatusRuntimeException e) {
            log.error("gRPC Error [ReqId: {}, Agent: {}]: {}", requestId, agentIp, e.getStatus());
            // 에러 발생 시 duration 0, logKey null 등으로 처리
            return new AgentResponse(null, false, 0, "Agent Network Error: " + e.getMessage(), 0, null);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Parameter serialization failed", e);
        }
    }
}