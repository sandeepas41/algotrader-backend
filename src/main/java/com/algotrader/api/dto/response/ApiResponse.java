package com.algotrader.api.dto.response;

import java.time.Instant;
import lombok.Getter;

@Getter
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final Instant timestamp;

    private ApiResponse(T data) {
        this.success = true;
        this.data = data;
        this.timestamp = Instant.now();
    }

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
