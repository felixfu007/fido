package com.fido.server.service;

/**
 * 內部可觀測性業務指標的名稱 / tag key 常數（CLAUDE.md「內部可觀測性 + 唯讀 list-tenants」交辦
 * 第 2 點，名稱與標籤規則已由 systems-analyst 定案，本類別只是集中常數避免多處字面字串打錯字）。
 *
 * <p>四個 counter 一律經由 Actuator 自動組態提供的
 * {@link io.micrometer.core.instrument.MeterRegistry} 記錄，一併發布到管理端口的
 * {@code /actuator/metrics}、{@code /actuator/prometheus}（見 application.yml）。
 *
 * <p><b>標籤硬規則（勿新增違反此規則的 tag）</b>：只允許低基數、非個資的固定列舉值 tag；嚴禁把
 * {@code user_ref_id}/{@code external_user_id}/{@code credential_id}/IP/{@code xdevId} 或任何
 * 使用者可控字串當 tag。唯一允許的租戶維度是 {@link #RATE_LIMIT_REJECTIONS} 上的
 * {@link #TAG_TENANT}（值取 {@code tenant_uid}）；其餘三個 counter 不加租戶 tag。
 */
public final class MetricNames {

    private MetricNames() {
    }

    /** {@code RateLimitService} 對某租戶回 429 時 +1。唯一帶 {@link #TAG_TENANT} tag 的 counter。 */
    public static final String RATE_LIMIT_REJECTIONS = "fido.ratelimit.rejections";

    /**
     * {@code AuthenticationService#verifyResult} 驗證失敗（任何 {@code ApiException}）時 +1，
     * 帶 {@link #TAG_REASON} tag，值為觸發的 {@link com.fido.server.exception.ErrorCode} 名稱
     * （如 {@code CREDENTIAL_REVOKED} / {@code ASSERTION_INVALID} / {@code SIGN_COUNTER_REGRESSION}）。
     */
    public static final String AUTH_VERIFY_FAILURES = "fido.auth.verify.failures";

    /** sign counter 倒退、credential/device 被自動撤銷時 +1。不帶任何 tag。 */
    public static final String CREDENTIAL_AUTO_REVOCATIONS = "fido.credential.auto_revocations";

    /** 跨裝置 QR 登入端點 C 判定 {@code proximityMismatch=true} 時 +1。不帶任何 tag。 */
    public static final String CROSSDEVICE_PROXIMITY_MISMATCH = "fido.crossdevice.proximity_mismatch";

    /** {@link #RATE_LIMIT_REJECTIONS} 專用 tag key，值為 {@code tenant.getTenantUid()} 字串。 */
    public static final String TAG_TENANT = "tenant";

    /** {@link #AUTH_VERIFY_FAILURES} 專用 tag key，值為 {@code ErrorCode} 名稱字串。 */
    public static final String TAG_REASON = "reason";
}
