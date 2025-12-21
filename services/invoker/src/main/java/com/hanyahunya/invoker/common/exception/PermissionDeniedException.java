package com.hanyahunya.invoker.common.exception;

import com.hanyahunya.invoker.common.error.ErrorCode;
import lombok.Getter;
import java.util.HashMap;
import java.util.Map;

@Getter
public class PermissionDeniedException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> payload = new HashMap<>();

    public PermissionDeniedException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public PermissionDeniedException(ErrorCode errorCode, Map<String, Object> payload) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        if (payload != null) {
            this.payload.putAll(payload);
        }
    }

    // 데이터를 함께 넘기는 생성자
    /*
        사용법: new PermissionDeniedException(
            ErrorCode,
            "funcId", 123,
            "userId", "userA"
        )
     */
    public PermissionDeniedException(ErrorCode errorCode, Object... keyValues) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;

        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Key-Value pairs must be even.");
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            this.payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
    }
}