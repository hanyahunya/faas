package com.hanyahunya.invoker.application.port.out;

import java.util.Map;

public interface AgentInvokePort {
    AgentResponse executeFunction(String agentIp, String sockPath, Map<String, Object> params);

    record AgentResponse(
            String result,
            boolean success,
            long memoryUsage,
            String errorMessage
    ) {}
}