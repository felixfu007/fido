package com.fido.server.domain.enums;

/**
 * 對應 db-schema.md 第 11 節 {@code cross_device_sessions.status} CHECK 約束（DB19）。
 * 單向狀態機：{@code PENDING -> SCANNED -> CONFIRMED -> CONSUMED}，另有 {@code DENIED}
 * （使用者取消 / 本機無對應 rpId 憑證）、{@code EXPIRED}（逾時）。
 *
 * <p>{@code docs/api-contract.md} §3.4.E（{@code POST .../sessions/{xdevId}/deny}，手機 App
 * 直連、{@code xdevId} capability）把 {@code PENDING}/{@code SCANNED} 顯式轉為 {@code DENIED}：
 * 使用者在確認畫面按「不是我，取消」（{@code reason=USER_CANCELLED}）或 claim 後發現本機無對應
 * rpId 憑證（{@code reason=NO_CREDENTIAL}）時由手機 App 呼叫，實作見
 * {@link com.fido.server.service.CrossDeviceLoginService#deny}。（先前版本此處記錄「四個端點
 * 沒有任何一個會轉 DENIED」是 v1 初版的已知規格缺口，已由 §3.4.E 補上並回填本 javadoc。）
 */
public enum CrossDeviceSessionStatus {
    PENDING,
    SCANNED,
    CONFIRMED,
    CONSUMED,
    DENIED,
    EXPIRED
}
