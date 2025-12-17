package com.hanyahunya.registry.adapter.out.security;

import com.hanyahunya.registry.application.port.in.TokensResult;
import com.hanyahunya.registry.application.port.out.TokenProviderPort;
import com.hanyahunya.registry.domain.exception.TokenCompromisedException;
import com.hanyahunya.registry.domain.model.Role;
import com.hanyahunya.registry.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProviderAdapter implements TokenProviderPort {
    private final JwtProperties jwtProps;

    private SecretKey accessKey;
    private SecretKey refreshKey;

    @PostConstruct
    public void init() {
        accessKey = Keys.hmacShaKeyFor(jwtProps.accessTokenSecret().getBytes(StandardCharsets.UTF_8));
        refreshKey = Keys.hmacShaKeyFor(jwtProps.refreshTokenSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TokensResult generateTokens(UUID userId, Role role, UUID tokenId) {
        return new TokensResult(issueAccess(userId, role, tokenId), issueRefresh(tokenId));
    }

    @Override
    public String generateAccessToken(UUID userId, Role role, UUID tokenId) {
        return issueAccess(userId, role, tokenId);
    }

    @Override
    public void validateExpiredAccessToken(String expiredAccessToken) {
        try {
            String userId = Jwts.parser()
                    .verifyWith(accessKey)
                    .build()
                    .parseSignedClaims(expiredAccessToken)
                    .getPayload()
                    .getSubject();
            throw new TokenCompromisedException(UUID.fromString(userId));
        } catch (ExpiredJwtException ignored) {}
    }

    @Override
    public Claims getRefreshClaims(String refreshToken) {
        return Jwts.parser()
                .verifyWith(refreshKey)
                .build()
                .parseSignedClaims(refreshToken)
                .getPayload();
    }

    private String issueAccess(UUID userId, Role role, UUID tokenId) {
        return Jwts.builder()
                .subject(userId.toString())
                .id(tokenId.toString())
                .claim("role", role.name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtProps.accessTokenExpiration().toMillis()))
                .signWith(accessKey)
                .compact();
    }

    private String issueRefresh(UUID tokenId) {
        return Jwts.builder()
                .id(tokenId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtProps.refreshTokenExpiration().toMillis()))
                .signWith(refreshKey)
                .compact();
    }
}
