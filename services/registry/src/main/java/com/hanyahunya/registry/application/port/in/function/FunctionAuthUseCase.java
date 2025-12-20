package com.hanyahunya.registry.application.port.in.function;

import java.util.UUID;

public interface FunctionAuthUseCase {
    boolean authFunction(Command command);

    record Command(
            UUID functionId,
            String accessKey
    ) {}
}
