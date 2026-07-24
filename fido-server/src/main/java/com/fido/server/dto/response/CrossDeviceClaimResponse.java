package com.fido.server.dto.response;

/**
 * 對應 api-contract.md 3.4.B response 200 body。{@code rpId}/{@code origin} 由伺服器依
 * {@code xdevId -> tenant} 權威給定，App 與 QR 都不得自行宣稱（見 origin-binding.md OB7）。
 */
public record CrossDeviceClaimResponse(
        String rpId,
        String origin,
        String tenantDisplayName,
        String challenge,
        String verificationCode,
        String expiresAt
) {
}
