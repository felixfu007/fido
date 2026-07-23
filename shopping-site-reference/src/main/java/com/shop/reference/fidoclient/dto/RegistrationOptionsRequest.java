package com.shop.reference.fidoclient.dto;

/**
 * 對應 docs/api-contract.md 2.1 {@code POST /api/v1/registration/options} request body。
 *
 * <p>這個 record 有兩種用途，{@code externalUserId} 的可信任程度不同：
 * <ol>
 *   <li><b>作為瀏覽器 → {@link com.shop.reference.registration.RegistrationProxyController}
 *       的 request body</b>：{@code externalUserId} 是選填的相容欄位，controller 一律改用
 *       目前登入 session 的 externalUserId（見 {@code ShopSessionService#requireSession}），
 *       如果這裡帶了值卻與 session 不一致會被拒絕，見 {@code ShopSessionService#resolveExternalUserId}。
 *       不可信任這個欄位本身。</li>
 *   <li><b>作為 {@link com.shop.reference.fidoclient.FidoServerClient#registrationOptions}
 *       實際打給 fido-server 的 request body</b>：此時 {@code externalUserId} 必須是
 *       controller 已解析、可信任的值（來自登入 session），不是直接轉發瀏覽器傳入的原始值。</li>
 * </ol>
 */
public record RegistrationOptionsRequest(
        String externalUserId,
        String displayName,
        String deviceLabel
) {
}
