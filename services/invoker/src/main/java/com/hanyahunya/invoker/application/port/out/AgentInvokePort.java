package com.hanyahunya.invoker.application.port.out;

import java.util.Map;
import java.util.UUID;

public interface AgentInvokePort {
    AgentResponse executeFunction(String agentIp, String sockPath, Map<String, Object> params, UUID requestId);

    record AgentResponse(
            String result,
            boolean success,
            long memoryUsage,
            String errorMessage,
            long durationMs,
            String logS3Key
    ) {}
}