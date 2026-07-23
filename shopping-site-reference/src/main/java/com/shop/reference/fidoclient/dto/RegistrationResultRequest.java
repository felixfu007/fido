package com.shop.reference.fidoclient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 對應 docs/api-contract.md 2.2 {@code POST /api/v1/registration/result} request body。
 *
 * <p>{@code externalUserId} 的可信任程度說明見 {@link RegistrationOptionsRequest} Javadoc ——
 * 同樣道理：作為瀏覽器傳入值時只是選填相容欄位（由
 * {@link com.shop.reference.registration.RegistrationProxyController} 驗證/改寫成 session 內
 * 的值），作為打給 fido-server 的實際 request body 時必須已經是可信任值。
 */
public record RegistrationResultRequest(
        @NotBlank String ceremonyId,
        String externalUserId,
        @NotNull @Valid CredentialAttestation credential,
        String deviceLabel
) {

    public record CredentialAttestation(
            @NotBlank String id,
            @NotBlank String rawId,
            @NotBlank String type,
            @NotNull @Valid AttestationResponse response
    ) {
    }

    public record AttestationResponse(
            @NotBlank String clientDataJSON,
            @NotBlank String attestationObject,
            List<String> transports
    ) {
    }
}
