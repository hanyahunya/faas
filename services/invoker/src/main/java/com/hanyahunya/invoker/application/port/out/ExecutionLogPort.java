package com.hanyahunya.invoker.application.port.out;

import com.hanyahunya.invoker.domain.model.ExecutionLog;

public interface ExecutionLogPort {
    void saveLog(ExecutionLog log);
}