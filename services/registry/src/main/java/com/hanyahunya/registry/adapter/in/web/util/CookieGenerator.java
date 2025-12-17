package com.hanyahunya.registry.adapter.in.web.util;

import com.hanyahunya.registry.global.config.CookieProperties;
import com.hanyahunya.registry.global.config.JwtProperties; // 지난번 만든 것
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieGenerator {
    private final CookieProperties cookieProps;
    private final JwtProperties jwtProps;

    public CookieGenerator(CookieProperties cookieProps, JwtProperties jwtProps) {
        this.cookieProps = cookieProps;
        this.jwtProps = jwtProps;
    }

    public ResponseCookie createRefreshTokenCookie(String refreshToken) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true)
                .secure(cookieProps.secure())
                .path(cookieProps.path())
                .maxAge(jwtProps.refreshTokenExpiration())
                .sameSite(cookieProps.sameSite());

        if (cookieProps.domain() != null && !cookieProps.domain().isBlank()) {
            builder.domain(cookieProps.domain());
        }

        return builder.build();
    }

    public ResponseCookie deleteRefreshTokenCookie() {
        return ResponseCookie.from("refresh_token", "")
                .maxAge(0)
                .path(cookieProps.path())
                .build();
    }
}