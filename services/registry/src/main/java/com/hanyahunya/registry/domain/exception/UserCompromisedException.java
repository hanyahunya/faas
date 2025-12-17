package com.hanyahunya.registry.domain.exception;

public class UserCompromisedException extends RuntimeException {
    public UserCompromisedException() {
        super("User Account compromised");
    }
}
