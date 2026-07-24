package com.fido.server.dto.response;

/** 對應 api-contract.md 3.4.A response 200 body。 */
public record CrossDeviceSessionCreateResponse(
        String xdevId,
        String qrUrl,
        String verificationCode,
        int expiresIn
) {
}
