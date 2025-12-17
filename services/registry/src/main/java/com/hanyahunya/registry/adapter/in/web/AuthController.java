package com.hanyahunya.registry.adapter.in.web;

import com.hanyahunya.registry.adapter.in.web.dto.LoginDto;
import com.hanyahunya.registry.adapter.in.web.dto.SignupDto;
import com.hanyahunya.registry.adapter.in.web.util.CookieGenerator;
import com.hanyahunya.registry.application.port.in.TokensResult;
import com.hanyahunya.registry.application.port.in.auth.LoginUseCase;
import com.hanyahunya.registry.application.port.in.auth.RegisterUserUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {
    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUseCase loginUseCase;
    private final CookieGenerator cookieGenerator;

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@RequestBody @Valid SignupDto signupDto) {
        registerUserUseCase.register(signupDto.toCommand());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/signin")
    public ResponseEntity<Void> signin(@RequestBody @Valid LoginDto loginDto) {
        TokensResult tokens = loginUseCase.login(loginDto.toCommand());
        HttpHeaders headers = createAuthHeaders(tokens);
        return ResponseEntity.ok().headers(headers).build();
    }

    private HttpHeaders createAuthHeaders(TokensResult tokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());
        ResponseCookie refreshTokenCookie = cookieGenerator.createRefreshTokenCookie(tokens.refreshToken());
        headers.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());
        return headers;
    }
}
