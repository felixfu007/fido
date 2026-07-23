package com.shop.reference.exception;

import java.util.Map;

/**
 * 購物網站對外（給自己前端）的統一錯誤格式。刻意與 fido-server 的格式（見
 * {@code docs/api-contract.md} 1.4）相似但獨立宣告 —— 購物網站不應該把 fido-server 的
 * 原始錯誤內容照單全收地暴露給使用者前端（例如內部 traceId 語意不同），但轉發
 * {@code code}/{@code message} 方便本示範頁面直接顯示、方便除錯。
 */
public record ShopErrorResponse(ErrorBody error) {

    public record ErrorBody(String source, String code, String message, Map<String, Object> details) {
    }

    public static ShopErrorResponse of(String source, String code, String message, Map<String, Object> details) {
        return new ShopErrorResponse(new ErrorBody(source, code, message, details == null ? Map.of() : details));
    }
}
