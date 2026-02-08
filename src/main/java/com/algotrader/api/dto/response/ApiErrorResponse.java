package com.algotrader.api.dto.response;

import com.algotrader.exception.ErrorCode;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
public class ApiErrorResponse {

    private final boolean success = false;
    private final ErrorDetail error;

    private ApiErrorResponse(ErrorDetail error) {
        this.error = error;
    }

    public static ApiErrorResponse of(ErrorCode errorCode, String message, Map<String, Object> details, String path) {
        ErrorDetail errorDetail = ErrorDetail.builder()
                .code(errorCode.getCode())
                .message(message)
                .details(details)
                .timestamp(Instant.now())
                .path(path)
                .build();
        return new ApiErrorResponse(errorDetail);
    }

    @Getter
    @Builder
    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final Map<String, Object> details;
        private final Instant timestamp;
        private final String path;
    }
}
