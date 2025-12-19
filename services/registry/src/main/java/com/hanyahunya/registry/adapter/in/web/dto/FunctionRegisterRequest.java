package com.hanyahunya.registry.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hanyahunya.registry.application.port.in.function.RegisterFunctionUseCase;
import com.hanyahunya.registry.domain.model.Runtime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

public record FunctionRegisterRequest(
        @NotBlank
        @JsonProperty("function_name")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "함수 이름은 영문, 숫자, _, - 만 가능합니다.")
        String functionName,

        @JsonProperty("description")
        String description,

        @JsonProperty("timeout")
        int timeout,

        @NotBlank
        @JsonProperty("runtime")
        Runtime runtime,

        @NotBlank
        @JsonProperty("code_content")
        String codeContent,

        @JsonProperty("env_vars")
        Map<String, String> envVars
) {
    public RegisterFunctionUseCase.Command toCommand(UUID userId) {
        return new RegisterFunctionUseCase.Command(
                userId,
                functionName,
                description,
                timeout,
                runtime,
                codeContent,
                envVars
        );
    }
}