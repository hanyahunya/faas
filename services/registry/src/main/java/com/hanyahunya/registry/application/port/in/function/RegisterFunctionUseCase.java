package com.hanyahunya.registry.application.port.in.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hanyahunya.registry.domain.model.Runtime;

import java.util.Map;
import java.util.UUID;

public interface RegisterFunctionUseCase {
    Result register(Command command);

    record Command(
            UUID userId,
            String functionName,
            String description,
            int timeout,
            Runtime runtime,
            String codeContent,
            Map<String, String> envVars
    ) {}

    record Result(
            @JsonProperty("function_id")
            UUID functionId,
            @JsonProperty("access_key")
            String accessKey
    ) {}
}
