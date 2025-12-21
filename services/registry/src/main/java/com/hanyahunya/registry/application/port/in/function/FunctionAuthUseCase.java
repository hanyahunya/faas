package com.hanyahunya.registry.application.port.in.function;

import java.util.UUID;

public interface FunctionAuthUseCase {
    Result authFunction(Command command);

    record Command(
            UUID functionId,
            String accessKey
    ) {}

    record Result(
            boolean isValid,
            String s3Key
    ) {}
}
