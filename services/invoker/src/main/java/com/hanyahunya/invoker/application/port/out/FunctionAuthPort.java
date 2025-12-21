package com.hanyahunya.invoker.application.port.out;

import java.util.UUID;

public interface FunctionAuthPort {
    boolean authenticateFunction(UUID functionId, String accessKey);
}
