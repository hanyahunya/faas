package com.hanyahunya.registry.application.port.in;

public record TokensResult(
        String accessToken,
        String refreshToken
) {
}
