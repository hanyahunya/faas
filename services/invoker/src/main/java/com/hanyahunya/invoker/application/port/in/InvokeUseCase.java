package com.hanyahunya.invoker.application.port.in;

import java.util.Map;
import java.util.UUID;

public interface InvokeUseCase {
    Result invoke(Command command);

    record Command(
            UUID functionId,
            String accessKey,
            Map<String, Object> params
    ) {}

    record Result(
            String result
    ) {}
}