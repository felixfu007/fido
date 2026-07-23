package com.shop.reference.fidoclient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 對應 docs/api-contract.md 2.1 {@code POST /api/v1/registration/options} request body。
 *
 * <p><b>正式串接注意</b>：{@code externalUserId} 在真正的購物網站裡必須來自購物網站自己「已用
 * 帳密登入」的 session（例如 {@code SecurityContextHolder} 或既有的 session/cookie），
 * 絕對不可以直接信任前端傳來的值 —— 否則任何呼叫端都能幫任意使用者發起 FIDO 裝置註冊。
 * 本參考範例為了讓靜態示範頁面單純（沒有實作真正的帳密登入），才讓前端直接帶入
 * {@code externalUserId}；請見 {@link com.shop.reference.registration.RegistrationProxyController}
 * 上的說明與 CLAUDE.md 開放問題。
 */
public record RegistrationOptionsRequest(
        @NotBlank String externalUserId,
        String displayName,
        String deviceLabel
) {
}
