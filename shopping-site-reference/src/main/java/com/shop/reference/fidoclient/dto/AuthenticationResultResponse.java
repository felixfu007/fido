package com.shop.reference.fidoclient.dto;

/**
 * 對應 docs/api-contract.md 3.2 response 200 body。
 *
 * <p><b>重要</b>：{@code verified()} 只是這個 JSON body 裡的一個布林欄位，本身不構成
 * 「使用者已通過 FIDO 硬體驗證」的密碼學證明 —— 任何能觀察/竄改 HTTP 回應的人都能偽造
 * 一個 {@code verified:true} 的 JSON。真正可信的證明是 {@link #session()} 內的
 * {@code token}（ES256 簽章的 JWT，見 docs/api-contract.md 1.3），購物網站後端必須實際
 * 驗證這個 JWT 的簽章 / iss / aud / exp，不能只看 {@code verified} 欄位就放行
 * （見 {@link com.shop.reference.authentication.AuthenticationProxyController}）。
 */
public record AuthenticationResultResponse(
        boolean verified,
        String externalUserId,
        String credentialId,
        String deviceId,
        SessionInfo session
) {

    public record SessionInfo(String token, String tokenType, int expiresIn) {
    }
}
