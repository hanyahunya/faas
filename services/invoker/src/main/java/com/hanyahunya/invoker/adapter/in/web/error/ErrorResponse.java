package com.hanyahunya.invoker.adapter.in.web.error;

import com.hanyahunya.invoker.common.error.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.ResponseEntity;

@Getter
@Builder
public class ErrorResponse {
    private final int status;
    private final String code;
    private final String message;

    // ErrorCode를 받아서 ErrorResponse 객체를 만드는 메서드
    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getHttpStatus().value())
                        .code(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
                );
    }

    // 메시지 커스텀 오버로딩 메서드
    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode, String customMessage) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getHttpStatus().value())
                        .code(errorCode.getCode())
                        .message(customMessage)
                        .build()
                );
    }
}