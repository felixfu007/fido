package com.fido.server.dto.response;

/** 對應 api-contract.md 4.2 response 200 body。 */
public record DeviceRevokeResponse(String deviceId, String status, String revokedAt) {
}
