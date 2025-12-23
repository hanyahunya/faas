package com.hanyahunya.invoker.adapter.out.persistence.repository;

import com.hanyahunya.invoker.domain.model.ExecutionLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ExecutionLogRepository extends MongoRepository<ExecutionLog, String> {
}
