package com.hanyahunya.invoker.adapter.out.persistence;

import com.hanyahunya.invoker.adapter.out.persistence.repository.ExecutionLogRepository;
import com.hanyahunya.invoker.application.port.out.ExecutionLogPort;
import com.hanyahunya.invoker.domain.model.ExecutionLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExecutionLogPersistenceAdapter implements ExecutionLogPort {

    private final ExecutionLogRepository repository;

    @Override
    public void saveLog(ExecutionLog log) {
        repository.save(log);
    }
}