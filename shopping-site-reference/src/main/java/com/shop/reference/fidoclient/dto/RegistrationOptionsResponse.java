package com.shop.reference.fidoclient.dto;

import java.util.List;

/**
 * 對應 docs/api-contract.md 2.1 response body。此物件直接轉交給瀏覽器前端餵給
 * {@code navigator.credentials.create()}（欄位名稱刻意與 WebAuthn
 * {@code PublicKeyCredentialCreationOptions} 對齊，見 2.1 範例 JSON）。
 */
public record RegistrationOptionsResponse(String ceremonyId, PublicKeyCredentialCreationOptions publicKey) {

    public record PublicKeyCredentialCreationOptions(
            RpEntity rp,
            UserEntity user,
            String challenge,
            List<PubKeyCredParam> pubKeyCredParams,
            long timeout,
            String attestation,
            AuthenticatorSelection authenticatorSelection,
            List<CredentialDescriptor> excludeCredentials
    ) {
    }

    public record RpEntity(String id, String name) {
    }

    public record UserEntity(String id, String name, String displayName) {
    }

    public record PubKeyCredParam(String type, int alg) {
    }

    public record AuthenticatorSelection(
            String authenticatorAttachment,
            String residentKey,
            boolean requireResidentKey,
            String userVerification
    ) {
    }
}
