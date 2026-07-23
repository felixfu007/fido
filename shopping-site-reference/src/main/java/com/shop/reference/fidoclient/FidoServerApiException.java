package com.shop.reference.fidoclient;

import org.springframework.http.HttpStatusCode;

import java.util.Map;

/**
 * fido-server 回傳非 2xx 時拋出，攜帶 docs/api-contract.md 1.4 通用錯誤格式的內容
 * （{@code code} / {@code message} / {@code traceId} / {@code details}），讓呼叫端
 * （購物網站的 controller）可以照 fido-server 的錯誤碼分流處理，而不是只看到一個
 * 籠統的 HTTP 例外。
 */
public class FidoServerApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String errorCode;
    private final String traceId;
    private final Map<String, Object> details;

    public FidoServerApiException(HttpStatusCode status, String errorCode, String message,
                                   String traceId, Map<String, Object> details) {
        super("fido-server 回應錯誤 [" + errorCode + "] " + message);
        this.status = status;
        this.errorCode = errorCode;
        this.traceId = traceId;
        this.details = details == null ? Map.of() : details;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getTraceId() {
        return traceId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
