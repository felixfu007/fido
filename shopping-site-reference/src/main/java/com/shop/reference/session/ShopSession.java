package com.shop.reference.session;

import java.time.Instant;

/**
 * 購物網站自己的登入 session（示範用，僅供這個參考範例展示「FIDO 驗證通過後，購物網站
 * 建立自己的 session」這一步該做什麼，不是要示範一套完整的 session 管理系統）。
 *
 * <p>正式系統應該用購物網站既有的 session/JWT/cookie 機制，這裡刻意用最陽春的
 * in-memory Map（見 {@link ShopSessionService}）避免喧賓奪主。
 */
public record ShopSession(String sessionId, String externalUserId, String fidoDeviceId,
                           String fidoCredentialId, Instant createdAt) {
}
