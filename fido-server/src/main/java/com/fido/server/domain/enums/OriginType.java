package com.fido.server.domain.enums;

/**
 * WebAuthn ceremony 的 origin 來源型別，記錄於 {@code audit_log.detail.originType}
 * （docs/origin-binding.md OB5 / api-contract.md D13）。不是任何表的欄位型別，純服務層/稽核用值。
 *
 * <ul>
 *   <li>{@code WEB}：origin 比對命中 {@code tenants.expected_origin}（瀏覽器情境）。</li>
 *   <li>{@code NATIVE_APP}：origin 比對命中該租戶 {@code tenant_app_bindings} 的 ACTIVE 列
 *       （原生 App 情境，opt-in）。</li>
 * </ul>
 */
public enum OriginType {
    WEB,
    NATIVE_APP
}
