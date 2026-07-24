package com.shop.reference.session;

/**
 * 目前登入 session 是經跨裝置 QR 登入（情境三）取得（session JWT {@code amr} 含
 * {@code "xdev"}），但呼叫端試圖執行一個本範例認定為「敏感操作」的動作（例如撤銷 FIDO 裝置）
 * 時拋出。
 *
 * <p><b>這是 docs/api-contract.md §1.3 / D17（以及 docs/vendor/api-integration-guide.md
 * §11.3）責任邊界的具體示範程式碼</b>：fido-server 只誠實在 session JWT 的 {@code amr}
 * 標記「這次登入是不是經防釣魚較弱的跨裝置路徑取得」，它不是身分來源、也不知道「本網站
 * 認為哪個操作算敏感」——enforcement 完全落在購物網站後端自己的授權層，如同
 * {@link ShopSessionService#resolveExternalUserId(ShopSession, String)} 示範的 D15
 * externalUserId 責任邊界一樣，都是「平台只給誠實的原始資料，把關責任交給呼叫端」的同一種
 * 設計哲學。
 *
 * <p>刻意設計成「明確拒絕」（403 {@code STEP_UP_REQUIRED}）而不是靜默降級或忽略——讓使用者
 * 清楚知道「必須先用同裝置重新驗證，才能執行這個操作」，而不是讓撤銷請求悄悄失敗或被略過。
 * 真正採用此範例模式的廠商，應該把此處判定的「敏感操作」清單換成自己的實際業務範圍（改密碼、
 * 金流、裝置撤銷/管理、修改個資等，見 api-integration-guide.md §11.3）。
 */
public class StepUpRequiredException extends RuntimeException {

    public StepUpRequiredException(String message) {
        super(message);
    }
}
