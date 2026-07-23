package com.shop.reference.fidoclient.dto;

import java.util.Map;

/**
 * 對應 docs/api-contract.md 1.4 通用錯誤格式：
 * {@code { "error": { "code", "message", "traceId", "details" } } }
 */
public record FidoErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, String traceId, Map<String, Object> details) {
    }
}
