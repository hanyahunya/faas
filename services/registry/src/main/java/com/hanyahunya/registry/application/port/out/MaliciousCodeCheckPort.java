package com.hanyahunya.registry.application.port.out;

import com.hanyahunya.registry.domain.model.Runtime;

public interface MaliciousCodeCheckPort {
    boolean isSafe(String codeContent);

    Runtime getRuntime();
}
