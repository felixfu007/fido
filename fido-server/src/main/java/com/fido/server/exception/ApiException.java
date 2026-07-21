package com.fido.server.exception;

import java.util.Map;

/**
 * 業務邏輯層的統一例外，攜帶 api-contract.md 1.4 通用錯誤格式所需的資訊。
 * 由 {@link GlobalExceptionHandler} 轉譯為對應 HTTP 狀態碼與 JSON body。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public ApiException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
