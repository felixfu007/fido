package com.fido.credentialprovider.crossdevice

/**
 * [com.fido.credentialprovider.ui.CrossDeviceLoginActivity] 的流程狀態機——純邏輯，不依賴任何
 * Android 框架類別，比照 `com.fido.credentialprovider.webauthn.OriginResolver` /
 * `com.fido.credentialprovider.ui.SetupStatusSupport` 既有的「框架無關邏輯抽出以利 JVM 單元測試」
 * 慣例（見 `CrossDeviceLoginFlowTest`）。
 *
 * 對應設計文件 `docs/decisions/qr-cross-device-login-design.md` 4.2 步驟 1–7 與硬性範圍邊界：
 * 本狀態機只涵蓋單一 ceremony「claim → （可能的多憑證選擇）→ 確認/取消 → 簽章 → 送出 result」的
 * 狀態轉移，**不含**任何裝置列表/撤銷/註冊等其他狀態（那會違反 CLAUDE.md「非獨立 APP 跳轉」
 * 決策的 `CrossDeviceLoginActivity` carve-out 硬性範圍邊界）。
 */
object CrossDeviceLoginFlow {

    /** claim 端點（`docs/api-contract.md` §3.4.B）回傳的權威 context，原封帶著走到後續步驟。 */
    data class ClaimContext(
        val xdevId: String,
        val rpId: String,
        val origin: String,
        val tenantDisplayName: String,
        val challengeB64Url: String,
        val verificationCode: String,
    )

    sealed class UiState {
        /** 初始 / 解析 deep link、呼叫 claim 端點期間。 */
        object Loading : UiState()

        /** deep link 格式不符（設計文件步驟 1）。 */
        data class InvalidLink(val reason: String) : UiState()

        /** claim 端點回傳非成功狀態（`404 XDEV_SESSION_NOT_FOUND` / `400 XDEV_SESSION_EXPIRED` /
         * `409 XDEV_SESSION_INVALID_STATE` 等，見 api-contract.md §3.4.B）。 */
        data class ClaimFailed(val reason: String) : UiState()

        /** 本機查無該 rpId 的 active 憑證（設計文件步驟 3 第一種情況）。 */
        data class NoCredentialForRp(val tenantDisplayName: String, val rpId: String) : UiState()

        /** 本機有多筆該 rpId 的 active 憑證，待使用者選擇（設計文件步驟 3 第二種情況）。 */
        data class SelectingCredential(val claim: ClaimContext, val credentialIds: List<String>) : UiState()

        /** Transaction Confirmation 確認畫面（設計文件步驟 4），待使用者按
         * 「確認登入」或「不是我，取消」。 */
        data class AwaitingConfirmation(val claim: ClaimContext, val credentialId: String) : UiState()

        /** 使用者按「不是我，取消」（設計文件步驟 4 取消分支）。 */
        object Denied : UiState()

        /** 使用者已確認，UV 通過後簽章 + 送出 result 期間（設計文件步驟 5–7）。 */
        object SigningAndSubmitting : UiState()

        /** result 端點回傳 `status=CONFIRMED`（設計文件步驟 7 成功分支）。`proximityMismatch`
         * 對應 api-contract.md §3.4.C `proximity.mismatch`——擁有者已拍板 proximity 只警示不阻擋
         * （S2），故不一致仍是成功狀態，只是多帶警示旗標供 UI 額外提醒。 */
        data class Confirmed(val proximityMismatch: Boolean) : UiState()

        /** UV 失敗、簽章例外或 result 端點回傳失敗（設計文件步驟 5–7 失敗分支）。 */
        data class SubmitFailed(val reason: String) : UiState()
    }

    fun onInvalidLink(reason: String): UiState = UiState.InvalidLink(reason)

    fun onClaimFailed(reason: String): UiState = UiState.ClaimFailed(reason)

    /** 對應設計文件 4.2 步驟 3：依本機憑證數量決定下一步。 */
    fun onClaimSucceeded(claim: ClaimContext, localCredentialIds: List<String>): UiState = when {
        localCredentialIds.isEmpty() -> UiState.NoCredentialForRp(claim.tenantDisplayName, claim.rpId)
        localCredentialIds.size == 1 -> UiState.AwaitingConfirmation(claim, localCredentialIds.first())
        else -> UiState.SelectingCredential(claim, localCredentialIds)
    }

    fun onCredentialChosen(claim: ClaimContext, credentialId: String): UiState =
        UiState.AwaitingConfirmation(claim, credentialId)

    fun onUserCancelled(): UiState = UiState.Denied

    fun onUserConfirmed(): UiState = UiState.SigningAndSubmitting

    fun onSubmitSucceeded(proximityMismatch: Boolean): UiState = UiState.Confirmed(proximityMismatch)

    fun onSubmitFailed(reason: String): UiState = UiState.SubmitFailed(reason)

    /**
     * 對應 `docs/api-contract.md` §3.4.E / D18：決定「使用者取消」與「本機無憑證」這兩個分支
     * 各自該以哪個 `reason` 呼叫 deny 端點。純函式、不做任何網路呼叫——實際呼叫由
     * [com.fido.credentialprovider.ui.CrossDeviceLoginActivity] 透過 [CrossDeviceDenyReporter]
     * 以 best-effort 方式觸發（見該類別檔頭「best-effort」語意說明）。其餘狀態不對應任何 deny
     * 呼叫，回傳 `null`。
     */
    fun denyReasonFor(state: UiState): CrossDeviceServerClient.DenyReason? = when (state) {
        is UiState.Denied -> CrossDeviceServerClient.DenyReason.USER_CANCELLED
        is UiState.NoCredentialForRp -> CrossDeviceServerClient.DenyReason.NO_CREDENTIAL
        else -> null
    }
}
