package com.fido.server.dto.response;

/** 對應 api-contract.md 5.1 response body。 */
public record FidoStatusResponse(String externalUserId, boolean enrolled, int activeDeviceCount, boolean canUseFido) {
}
