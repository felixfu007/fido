package com.fido.server.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 對應 api-contract.md 3.4.C {@code POST .../cross-device/sessions/{xdevId}/result} request
 * body（手機 App 直連，{@code xdevId} capability）：「標準 assertion JSON（結構同 §3.2 的
 * {@code credential.{id,rawId,type,response...}}）」——與同裝置 §3.2 不同，本端點的
 * {@code ceremonyId} 不在請求 body 內（伺服器由路徑上的 {@code xdevId} 反查對應的
 * {@code auth_challenges} ceremony，見 {@code CrossDeviceLoginService}），故本 record 直接對應
 * {@code credential} 物件本身，而非 {@link AuthenticationResultRequest} 那種
 * {@code {ceremonyId, credential}} 外層包裝。
 */
public record CrossDeviceResultRequest(
        @NotBlank String id,
        @NotBlank String rawId,
        @NotBlank String type,
        @NotNull @Valid AuthenticationResultRequest.AssertionResponse response
) {

    /** 轉為既有 {@link AuthenticationResultRequest.CredentialAssertion}，供重用 verifyResult。 */
    public AuthenticationResultRequest.CredentialAssertion toCredentialAssertion() {
        return new AuthenticationResultRequest.CredentialAssertion(id, rawId, type, response);
    }
}
