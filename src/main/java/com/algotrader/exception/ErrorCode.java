package com.algotrader.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    VALIDATION_ERROR("VALIDATION_ERROR", 400),
    BAD_REQUEST("BAD_REQUEST", 400),
    UNAUTHORIZED("UNAUTHORIZED", 401),
    FORBIDDEN("FORBIDDEN", 403),
    NOT_FOUND("NOT_FOUND", 404),
    RISK_LIMIT_EXCEEDED("RISK_LIMIT_EXCEEDED", 422),
    INTERNAL_ERROR("INTERNAL_ERROR", 500),
    BROKER_ERROR("BROKER_ERROR", 502);

    private final String code;
    private final int httpStatus;
}
