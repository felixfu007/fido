package com.fido.server.dto.response;

/** 對應 api-contract.md 3.2 response 200 body。 */
public record AuthenticationResultResponse(
        boolean verified,
        String externalUserId,
        String credentialId,
        String deviceId,
        SessionInfo session
) {

    public record SessionInfo(String token, String tokenType, int expiresIn) {
    }
}
