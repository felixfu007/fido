package com.shop.reference.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 從一次 HTTP 請求解析「發出這次請求的瀏覽器真實 client IP」。
 *
 * <p>目前本專案唯一需要這件事的地方是跨裝置 QR 登入（情境三，見
 * docs/api-contract.md §3.4）：購物網站後端要把「桌機瀏覽器的真實 client IP」轉發給
 * fido-server（{@code desktopClientIp}），供其 proximity 檢查使用（fido-server 直接看到的
 * 來源 IP 是購物網站後端自己的 IP，不是桌機——見設計文件 5.2.4 附註）。專案裡先前沒有其他
 * 端點需要取用 client IP，因此這是本次新增的最小工具方法，不是重構既有邏輯。
 *
 * <p><b>信任層級（僅供示範，非通用防偽 IP 方案）</b>：優先採 {@code X-Forwarded-For} 的第一段
 * （慣例上是離用戶端最近、由第一層反向代理寫入的原始位址），退而求其次用
 * {@link HttpServletRequest#getRemoteAddr()}。{@code X-Forwarded-For} 可被客戶端任意偽造，
 * 正式環境必須確保這個 header 只由受信任的反向代理層（而非公開可達的來源）寫入/覆蓋，否則
 * 這裡解析出的「client IP」形同使用者自己說了算，proximity 檢查的防護力會被架空——這個信任
 * 假設的建立與維護屬於部署層責任（反向代理設定），不是這個工具方法能保證的事。
 */
public final class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // X-Forwarded-For 可能是逗號分隔的多段（每經過一層代理追加一段），第一段是原始用戶端。
            String first = forwardedFor.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
