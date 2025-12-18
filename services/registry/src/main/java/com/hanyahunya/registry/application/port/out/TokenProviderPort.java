package com.hanyahunya.registry.application.port.out;

import com.hanyahunya.registry.application.port.in.TokensResult;
import com.hanyahunya.registry.domain.model.Role;
import io.jsonwebtoken.Claims;

import java.util.UUID;

public interface TokenProviderPort {
    TokensResult generateTokens(UUID userId, Role role, UUID tokenId);

    String generateAccessToken(UUID userId, Role role, UUID tokenId);

    void validateExpiredAccessToken(String expiredAccessToken);

    Claims getRefreshClaims(String refreshToken);
}
