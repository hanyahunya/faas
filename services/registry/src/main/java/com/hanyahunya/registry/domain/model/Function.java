package com.hanyahunya.registry.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(name = "functions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Function {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "function_id", columnDefinition = "BINARY(16)")
    private UUID functionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false)
    private String functionName;

    @Column(name = "description")
    private String description;

    @Column(name = "timeout", nullable = false)
    private int timeout;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime", nullable = false)
    private Runtime runtime;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Function(UUID functionId, UUID userId, String functionName, String description, int timeout, Runtime runtime, String s3Key) {
        this.functionId = functionId;
        this.user = User.idBuilder().userId(userId).build();
        this.createdAt = LocalDateTime.now();

        update(functionName, description, timeout, runtime, s3Key);
    }

    private static final int DEFAULT_TIMEOUT = 300;
    private static final int MAX_TIMEOUT = 3600;
    public void update(String functionName, String description, int timeout, Runtime runtime, String s3Key) {
        this.functionName = functionName;
        this.description = description;
        int effectiveTimeout = (timeout == 0) ? DEFAULT_TIMEOUT : timeout;
        this.timeout = Math.min(effectiveTimeout, MAX_TIMEOUT);
        this.runtime = runtime;
        this.s3Key = s3Key;
    }
}
