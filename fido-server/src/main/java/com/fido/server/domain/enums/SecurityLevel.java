package com.fido.server.domain.enums;

/**
 * 對應 db-schema.md `bound_devices.security_level` CHECK 約束（僅允許硬體安全區）。
 * `SOFTWARE` 等級（若偵測到）在註冊階段即以 422 HARDWARE_SECURITY_NOT_MET 拒絕、不落庫，
 * 因此本 enum 不含 SOFTWARE；SOFTWARE 僅作為驗證流程中間態的偵測結果表示。
 */
public enum SecurityLevel {
    TEE,
    STRONG_BOX
}
