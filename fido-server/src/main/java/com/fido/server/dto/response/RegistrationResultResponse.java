package com.fido.server.dto.response;

/** 對應 api-contract.md 2.2 response 201 body。 */
public record RegistrationResultResponse(
        String credentialId,
        String deviceId,
        DeviceInfo device,
        long signCount
) {

    public record DeviceInfo(String deviceName, String aaguid, String securityLevel, String createdAt) {
    }
}
