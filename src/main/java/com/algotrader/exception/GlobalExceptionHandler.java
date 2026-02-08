package com.algotrader.exception;

import com.algotrader.api.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> details = new HashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        return buildResponse(ErrorCode.VALIDATION_ERROR, "Validation failed", details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, Object> details = new HashMap<>();
        ex.getConstraintViolations()
                .forEach(violation -> details.put(violation.getPropertyPath().toString(), violation.getMessage()));
        return buildResponse(ErrorCode.VALIDATION_ERROR, "Validation failed", details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildResponse(ErrorCode.BAD_REQUEST, "Malformed request body", null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return buildResponse(ErrorCode.NOT_FOUND, ex.getMessage(), null, request);
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiErrorResponse> handleBase(BaseException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode.getHttpStatus() >= 500) {
            log.error("Server error: {}", ex.getMessage(), ex);
        } else {
            log.warn("Client error: {}", ex.getMessage());
        }
        return buildResponse(errorCode, ex.getMessage(), ex.getDetails(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        return buildResponse(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", null, request);
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            ErrorCode errorCode, String message, Map<String, Object> details, HttpServletRequest request) {
        ApiErrorResponse response = ApiErrorResponse.of(errorCode, message, details, request.getRequestURI());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }
}
