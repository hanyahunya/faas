package com.hanyahunya.registry.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "password")
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private Status status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder(builderClassName = "SignupBuilder", builderMethodName = "signupBuilder")
    public User(String email, String password) {
        this.userId = UUID.randomUUID();
        this.email = email;
        this.password = password;
        this.role = Role.ROLE_USER;
        this.status = Status.PENDING_APPROVAL;
        this.createdAt = LocalDateTime.now();
    }

    @Builder(builderClassName = "IdBuilder", builderMethodName = "idBuilder")
    public User(UUID userId) {
        this.userId = userId;
    }
}
