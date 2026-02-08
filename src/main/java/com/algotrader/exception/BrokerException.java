package com.algotrader.exception;

public class BrokerException extends BaseException {

    public BrokerException(String message) {
        super(ErrorCode.BROKER_ERROR, message);
    }

    public BrokerException(String message, Throwable cause) {
        super(ErrorCode.BROKER_ERROR, message, cause);
    }
}
