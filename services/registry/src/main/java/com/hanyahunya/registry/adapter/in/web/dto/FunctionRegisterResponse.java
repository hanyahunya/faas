package com.hanyahunya.registry.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FunctionRegisterResponse(
        @JsonProperty("function_id")
        String functionId,
        @JsonProperty("massage")
        String message
) {
        public FunctionRegisterResponse(String functionId, String message) {
                this.functionId = functionId;
                this.message = message;
        }
}
