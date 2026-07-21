package com.fido.server.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 對應 api-contract.md 3.2 POST /api/v1/authentication/result request body。 */
public record AuthenticationResultRequest(
        @NotBlank String ceremonyId,
        @NotNull @Valid CredentialAssertion credential
) {

    public record CredentialAssertion(
            @NotBlank String id,
            @NotBlank String rawId,
            @NotBlank String type,
            @NotNull @Valid AssertionResponse response
    ) {
    }

    public record AssertionResponse(
            @NotBlank String clientDataJSON,
            @NotBlank String authenticatorData,
            @NotBlank String signature,
            String userHandle
    ) {
    }
}
