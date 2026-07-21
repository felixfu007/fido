package com.fido.server.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 對應 api-contract.md 2.1 POST /api/v1/registration/options request body。 */
public record RegistrationOptionsRequest(
        @NotBlank String externalUserId,
        String displayName,
        String deviceLabel
) {
}
