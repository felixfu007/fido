package com.fido.server.dto.response;

import java.util.List;

/** 對應 api-contract.md 3.1 response body。 */
public record AuthenticationOptionsResponse(String ceremonyId, AssertionOptions publicKey) {

    public record AssertionOptions(
            String challenge,
            long timeout,
            String rpId,
            String userVerification,
            List<CredentialDescriptor> allowCredentials
    ) {
    }
}
