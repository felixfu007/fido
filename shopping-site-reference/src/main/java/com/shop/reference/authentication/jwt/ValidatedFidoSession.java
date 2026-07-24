package com.shop.reference.authentication.jwt;

import java.util.List;

/**
 * {@link FidoSessionJwtValidator#validate(String)} 通過全部檢核後回傳的結果，內容取自
 * docs/api-contract.md 1.3 定義的 JWT claims（{@code sub}/{@code tid}/{@code cid}/
 * {@code did}/{@code amr}/{@code auth_time}/{@code jti}）。只有走過完整驗證流程才拿得到這個
 * 物件，刻意不提供其他建構方式，避免呼叫端不小心繞過驗證邏輯就取得「看似已驗證」的資料。
 */
public record ValidatedFidoSession(
        String externalUserId,
        String tenantId,
        String credentialId,
        String deviceId,
        List<String> amr,
        long authTimeEpochSeconds,
        String jti
) {

    /**
     * 是否經由跨裝置 QR 登入（情境三，見 docs/api-contract.md §1.3 / D17）取得這枚 session。
     *
     * <p>這是購物網站（或任何驗證此 JWT 的下游）辨識「本次 session 是否經 cross-device
     * 較弱路徑取得」的唯一權威依據——fido-server 只誠實標記，不強制下游怎麼用（D15 責任邊界
     * 的延伸）。下游必須自行對敏感操作要求 step-up（見
     * {@link com.shop.reference.device.DeviceProxyController#revoke} 的示範）。
     */
    public boolean isCrossDeviceLogin() {
        return amr != null && amr.contains("xdev");
    }
}
