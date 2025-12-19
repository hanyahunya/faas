package com.hanyahunya.registry.application.port.in.function;

import com.hanyahunya.registry.domain.model.Runtime;

import java.util.Map;
import java.util.UUID;

public interface RegisterFunctionUseCase {
    UUID register(Command command);

    record Command(
            UUID userId,
            String functionName,
            String description,
            int timeout,
            Runtime runtime,
            String codeContent,
            Map<String, String> envVars
    ) {}
}
