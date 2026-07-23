package com.shop.reference.session;

/**
 * 呼叫端沒有帶（或帶了無效的）{@link ShopSessionService#COOKIE_NAME} cookie 時拋出。
 *
 * <p>凡是需要「目前登入的購物網站使用者」才能執行的端點（例如發起 FIDO 裝置註冊、
 * 列出/撤銷自己的裝置），都必須先透過 {@link ShopSessionService#requireSession(String)}
 * 取得 session，取不到就代表使用者尚未登入，直接拒絕整個請求 —— 不可以退而求其次去
 * 相信請求裡呼叫端自己宣稱的 externalUserId。
 */
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException(String message) {
        super(message);
    }
}
