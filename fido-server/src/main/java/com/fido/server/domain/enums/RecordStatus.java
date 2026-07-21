package com.fido.server.domain.enums;

/**
 * 對應 db-schema.md `fido_credentials.status` 與 `bound_devices.status`
 * 共用的 CHECK 約束（ACTIVE / REVOKED）。
 */
public enum RecordStatus {
    ACTIVE,
    REVOKED
}
