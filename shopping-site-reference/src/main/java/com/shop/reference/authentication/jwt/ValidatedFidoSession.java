package com.shop.reference.authentication.jwt;

/**
 * {@link FidoSessionJwtValidator#validate(String)} 通過全部檢核後回傳的結果，內容取自
 * docs/api-contract.md 1.3 定義的 JWT claims（{@code sub}/{@code tid}/{@code cid}/
 * {@code did}/{@code auth_time}/{@code jti}）。只有走過完整驗證流程才拿得到這個物件，
 * 刻意不提供其他建構方式，避免呼叫端不小心繞過驗證邏輯就取得「看似已驗證」的資料。
 */
public record ValidatedFidoSession(
        String externalUserId,
        String tenantId,
        String credentialId,
        String deviceId,
        long authTimeEpochSeconds,
        String jti
) {
}
