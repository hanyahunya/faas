package com.hanyahunya.invoker.domain.error;

import com.hanyahunya.invoker.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FunctionErrorCode implements ErrorCode {

    FUNCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "F-001", "존재하지 않는 함수입니다."),
    INVOKE_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "F-002", "함수 실행 권한이 없습니다."),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "F-003", "실행 할당량을 초과했습니다."),
    CONTAINER_INIT_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "F-004", "컨테이너 초기화 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}