package com.fido.server.domain.enums;

/**
 * 對應 db-schema.md `fido_credentials.revoked_reason` / `bound_devices.revoked_reason`
 * CHECK 約束。
 */
public enum RevokedReason {
    USER_REQUEST,
    COUNTER_REGRESSION,
    ADMIN,
    TENANT_DISABLED
}
