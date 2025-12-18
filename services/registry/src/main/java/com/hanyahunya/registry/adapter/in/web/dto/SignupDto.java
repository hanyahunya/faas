package com.hanyahunya.registry.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hanyahunya.registry.application.port.in.auth.RegisterUserUseCase;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupDto(
        @Email
        @NotBlank
        @JsonProperty("email")
        String email,

        @NotBlank
        @JsonProperty("password")
        String password
) {
    public RegisterUserUseCase.Command toCommand() {
        return new RegisterUserUseCase.Command(email, password);
    }
}