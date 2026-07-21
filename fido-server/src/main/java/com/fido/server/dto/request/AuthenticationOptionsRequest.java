package com.fido.server.dto.request;

/**
 * 對應 api-contract.md 3.1 POST /api/v1/authentication/options request body。
 * {@code externalUserId} 為選填：省略則走 discoverable / usernameless 流程。
 */
public record AuthenticationOptionsRequest(String externalUserId) {
}
