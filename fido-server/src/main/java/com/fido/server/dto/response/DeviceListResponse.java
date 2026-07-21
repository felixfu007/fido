package com.fido.server.dto.response;

import java.util.List;

/** 對應 api-contract.md 4.1 response body。 */
public record DeviceListResponse(String externalUserId, List<DeviceSummary> devices, String nextCursor) {

    public record DeviceSummary(
            String deviceId,
            String deviceName,
            String model,
            String osVersion,
            String securityLevel,
            String aaguid,
            String credentialId,
            String status,
            String createdAt,
            String lastUsedAt
    ) {
    }
}
