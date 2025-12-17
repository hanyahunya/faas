package com.hanyahunya.registry.domain.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class TokenCompromisedException extends RuntimeException {
    private final UUID userId;

    public TokenCompromisedException(String message, UUID userId) {
        super(message);
        this.userId = userId;
    }

    public TokenCompromisedException(UUID userId) {
        super("Token is compromised");
        this.userId = userId;
    }
}
