package com.fido.server.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 對應 api-contract.md 2.2 POST /api/v1/registration/result request body。 */
public record RegistrationResultRequest(
        @NotBlank String ceremonyId,
        @NotBlank String externalUserId,
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
