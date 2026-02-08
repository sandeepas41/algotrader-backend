package com.algotrader.exception;

import java.util.Map;

public class BusinessException extends BaseException {

    public BusinessException(String message) {
        super(ErrorCode.RISK_LIMIT_EXCEEDED, message);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(String message, Map<String, Object> details) {
        super(ErrorCode.RISK_LIMIT_EXCEEDED, message, details);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details);
    }
}
