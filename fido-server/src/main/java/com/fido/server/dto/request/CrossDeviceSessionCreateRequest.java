package com.fido.server.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 對應 api-contract.md 3.4.A {@code POST /api/v1/authentication/cross-device/sessions}
 * request body（購物網站後端，X-API-Key）。
 */
public record CrossDeviceSessionCreateRequest(
        @NotBlank String desktopClientIp
) {
}
