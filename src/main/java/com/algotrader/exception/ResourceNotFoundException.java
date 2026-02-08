package com.algotrader.exception;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(ErrorCode.NOT_FOUND, String.format("%s not found with identifier: %s", resourceType, identifier));
    }
}
