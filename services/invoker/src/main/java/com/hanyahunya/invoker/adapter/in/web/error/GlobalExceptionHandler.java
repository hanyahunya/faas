package com.hanyahunya.invoker.adapter.in.web.error;

import com.hanyahunya.invoker.common.error.GlobalErrorCode;
import com.hanyahunya.invoker.common.exception.BusinessException;
import com.hanyahunya.invoker.common.exception.PermissionDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionDeniedException(PermissionDeniedException e) {
        log.warn("Permission Denied: {}", e.getMessage());

        return ErrorResponse.toResponseEntity(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.warn("Business Exception: [Code: {}] {}", e.getErrorCode().getCode(), e.getMessage());

        return ErrorResponse.toResponseEntity(e.getErrorCode());
    }

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException(ClientAbortException e) {
        log.warn("Client terminated connection: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled Exception: ", e);
        return ErrorResponse.toResponseEntity(GlobalErrorCode.INTERNAL_SERVER_ERROR);
    }
}