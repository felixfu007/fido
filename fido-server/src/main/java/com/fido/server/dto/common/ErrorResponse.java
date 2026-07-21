package com.fido.server.dto.common;

import java.util.Map;

/**
 * 對應 api-contract.md 1.4 通用錯誤格式：
 * {@code { "error": { "code", "message", "traceId", "details" } } }
 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, String traceId, Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message, String traceId, Map<String, Object> details) {
        return new ErrorResponse(new ErrorBody(code, message, traceId, details == null ? Map.of() : details));
    }
}
