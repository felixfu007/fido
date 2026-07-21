package com.fido.server.context;

/**
 * 每個請求的追蹤 ID（對應 X-Request-Id / 錯誤回應 traceId），以 ThreadLocal 承載。
 * 由 {@code RequestContextFilter} 於請求開始時設定、結束時清除。
 */
public final class RequestContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
