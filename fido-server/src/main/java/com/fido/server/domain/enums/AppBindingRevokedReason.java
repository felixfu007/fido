package com.fido.server.domain.enums;

/**
 * 對應 db-schema.md 第 9 節 {@code tenant_app_bindings.revoked_reason} CHECK 約束。
 *
 * <p>刻意不重用 {@link RevokedReason}：後者是 {@code fido_credentials} /
 * {@code bound_devices} 共用的裝置/憑證層撤銷原因集合（含 {@code COUNTER_REGRESSION}、
 * {@code TENANT_DISABLED} 等與「簽章行為」相關的語意），與本表撤銷一支已登錄 App 授權
 * （簽章輪替、人工下架、安全事件）語意不同，兩份 CHECK 約束的允許值本來就不同
 * （見 db-schema.md 第 9 節 DDL）。
 */
public enum AppBindingRevokedReason {
    ADMIN,
    KEY_ROTATION,
    SECURITY
}
