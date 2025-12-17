package com.hanyahunya.registry.global;

import com.hanyahunya.registry.domain.exception.EmailAlreadyExistsException;
import com.hanyahunya.registry.domain.exception.LoginFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Void> handleEmailAlreadyExistsException(EmailAlreadyExistsException e) {
        log.warn("EmailAlreadyExistsException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(LoginFailedException.class)
    public ResponseEntity<ErrorResponse> handleLoginFailedException(LoginFailedException e) {

        if (e.getStatus() != null) {
            return switch (e.getStatus()) {
                case PENDING_APPROVAL -> ResponseEntity
                        .status(HttpStatus.FORBIDDEN) // 403
                        .body(new ErrorResponse("ACCOUNT_PENDING", "관리자 승인 대기 중인 계정입니다."));

                case COMPROMISED -> ResponseEntity
                        .status(HttpStatus.LOCKED) // 423
                        .body(new ErrorResponse("ACCOUNT_LOCKED", "보안 문제로 잠긴 계정입니다."));

                case ACTIVE -> ResponseEntity // 이론상 올일이 없지만 방어코드
                        .status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("LOGIN_FAILED", "로그인 처리에 실패했습니다."));
            };
        }

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED) // 401
                .body(new ErrorResponse("LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다."));
    }
}
