package com.fido.server.domain.enums;

/** 對應 db-schema.md 第 10 節 `signing_keys.status` CHECK 約束（DB18）。 */
public enum SigningKeyStatus {
    ACTIVE,
    RETIRED
}
