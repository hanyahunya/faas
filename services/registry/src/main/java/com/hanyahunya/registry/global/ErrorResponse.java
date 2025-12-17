package com.hanyahunya.registry.global;

import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        Map<String, String> validation
) {
    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}