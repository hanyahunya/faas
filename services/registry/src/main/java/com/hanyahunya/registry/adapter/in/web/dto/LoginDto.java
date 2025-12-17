package com.hanyahunya.registry.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hanyahunya.registry.application.port.in.auth.LoginUseCase;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginDto (
        @Email
        @NotBlank
        @JsonProperty("email")
        String email,

        @NotBlank
        @JsonProperty("password")
        String password
) {
    public LoginUseCase.Command toCommand() {
        return new LoginUseCase.Command(email, password);
    }
}
