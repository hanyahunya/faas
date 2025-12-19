package com.hanyahunya.registry.domain.repository;

import com.hanyahunya.registry.domain.model.Function;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FunctionRepository extends JpaRepository<Function, UUID> {
}
