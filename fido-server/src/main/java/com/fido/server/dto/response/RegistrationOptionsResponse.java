package com.fido.server.dto.response;

import java.util.List;

/** 對應 api-contract.md 2.1 response body。 */
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
