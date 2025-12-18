package com.hanyahunya.registry.domain.exception;

import com.hanyahunya.registry.domain.model.Status;
import lombok.Getter;

@Getter
public class LoginFailedException extends RuntimeException {
  private final Status status;

    public LoginFailedException() {
        super("Login failed");
        this.status = null;
    }

    public LoginFailedException(Status status) {
        super("Login failed due to status: " + status);
        this.status = status;
    }
}
