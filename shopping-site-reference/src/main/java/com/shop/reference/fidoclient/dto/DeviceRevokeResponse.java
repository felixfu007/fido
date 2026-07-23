package com.shop.reference.fidoclient.dto;

/** 對應 docs/api-contract.md 4.2 response 200 body。 */
public record DeviceRevokeResponse(String deviceId, String status, String revokedAt) {
}
