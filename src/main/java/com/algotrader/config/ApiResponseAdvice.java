package com.algotrader.config;

import com.algotrader.api.dto.response.ApiErrorResponse;
import com.algotrader.api.dto.response.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {

        String path = request.getURI().getPath();

        // Skip actuator and error endpoints
        if (path.startsWith("/actuator") || path.equals("/error")) {
            return body;
        }

        // Skip already-wrapped responses
        if (body instanceof ApiResponse<?> || body instanceof ApiErrorResponse) {
            return body;
        }

        // Skip String return types (StringHttpMessageConverter can't serialize ApiResponse)
        if (StringHttpMessageConverter.class.isAssignableFrom(selectedConverterType)) {
            return body;
        }

        return ApiResponse.of(body);
    }
}
