package com.shop.reference.fidoclient.dto;

/**
 * 對應 docs/api-contract.md §3.4.D {@code GET .../cross-device/sessions/{xdevId}/status}
 * response 200 body。
 *
 * <p>{@code status} 為 {@code PENDING}/{@code SCANNED} 時 {@code session} 為 {@code null}
 * （桌機應繼續輪詢）；為 {@code CONFIRMED} 時帶 {@code session}（一次性 JWT，領取後
 * fido-server 立即把該 xdev session 轉 {@code CONSUMED}）；為 {@code DENIED}/{@code EXPIRED}
 * 時桌機應停止輪詢並顯示對應訊息。
 *
 * <p>與 {@link AuthenticationResultResponse} 的信任邊界完全相同：{@code status} 本身只是一個
 * 可能被竄改的欄位，真正的登入證明是 {@code session.token()} 通過
 * {@link com.shop.reference.authentication.jwt.FidoSessionJwtValidator#validate(String)}
 * 驗簽——見 {@link com.shop.reference.authentication.ShopLoginFinalizer}。
 */
public record CrossDeviceStatusResponse(String status, SessionInfo session, Warnings warnings) {

    public record SessionInfo(String token, String tokenType, int expiresIn) {
    }

    /** {@code proximityMismatch}：桌機/手機出口 IP 不一致的警示（S2 拍板為只警示不阻擋，登入仍可能成功）。 */
    public record Warnings(boolean proximityMismatch) {
    }
}
