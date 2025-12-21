package com.hanyahunya.invoker.application.port.out;

import java.util.UUID;

public interface FunctionAuthPort {
    Result authenticateFunction(UUID functionId, String accessKey);

    record Result(
            boolean isValid,
            String s3Key
    ) {}
}
