package com.hanyahunya.registry.application.port.in.token;

import com.hanyahunya.registry.application.port.in.TokensResult;

public interface RefreshTokenUseCase {
    TokensResult refresh(String expiredAccessToken, String refreshToken);
}
