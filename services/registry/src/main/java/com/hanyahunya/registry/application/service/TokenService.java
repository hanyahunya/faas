package com.hanyahunya.registry.application.service;

import com.hanyahunya.registry.application.port.in.TokensResult;
import com.hanyahunya.registry.application.port.in.token.RefreshTokenUseCase;
import com.hanyahunya.registry.application.port.out.TokenEncodePort;
import com.hanyahunya.registry.application.port.out.TokenProviderPort;
import com.hanyahunya.registry.domain.model.Token;
import com.hanyahunya.registry.domain.model.User;
import com.hanyahunya.registry.domain.repository.TokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService implements RefreshTokenUseCase {
    private final TokenProviderPort tokenProviderPort;
    private final TokenEncodePort encoder;

    private final TokenRepository tokenRepository;

    public TokensResult issueToken(User user) {
        UUID tokenId = UUID.randomUUID();
        TokensResult tokens = tokenProviderPort.generateTokens(user.getUserId(), user.getRole(), tokenId);

        Token token = Token.builder()
                .tokenId(tokenId)
                .user(user)
                .accessTokenHash(encoder.encode(tokens.accessToken()))
                .refreshTokenHash(encoder.encode(tokens.refreshToken()))
                .build();
        tokenRepository.save(token);
        return tokens;
    }

    @Override
    public TokensResult refresh(String expiredAccessToken, String refreshToken) {
        return null;
    }
}
