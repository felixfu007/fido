package com.shop.reference.session;

import java.time.Instant;

/**
 * 購物網站自己的登入 session（示範用，僅供這個參考範例展示「FIDO 驗證通過後，購物網站
 * 建立自己的 session」這一步該做什麼，不是要示範一套完整的 session 管理系統）。
 *
 * <p>正式系統應該用購物網站既有的 session/JWT/cookie 機制，這裡刻意用最陽春的
 * in-memory Map（見 {@link ShopSessionService}）避免喧賓奪主。
 *
 * <p>{@code crossDeviceLogin}：是否經由跨裝置 QR 登入（情境三）取得（來自 session JWT
 * 的 {@code amr} 含 {@code "xdev"}，見
 * {@link com.shop.reference.authentication.jwt.ValidatedFidoSession#isCrossDeviceLogin()}）。
 * 供 {@link com.shop.reference.device.DeviceProxyController} 對敏感操作做 step-up 檢查示範
 * （docs/api-contract.md §1.3 / D17）。舊有 5-arg 建構子保留、預設 {@code false}，避免同裝置
 * 登入（§3.2）與示範假登入（{@link com.shop.reference.session.DemoLoginController}）的既有呼叫
 * 端都要跟著改。
 */
public record ShopSession(String sessionId, String externalUserId, String fidoDeviceId,
                           String fidoCredentialId, boolean crossDeviceLogin, Instant createdAt) {

    public ShopSession(String sessionId, String externalUserId, String fidoDeviceId,
                        String fidoCredentialId, Instant createdAt) {
        this(sessionId, externalUserId, fidoDeviceId, fidoCredentialId, false, createdAt);
    }
}
