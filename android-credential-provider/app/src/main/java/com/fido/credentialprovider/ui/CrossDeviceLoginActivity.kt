package com.fido.credentialprovider.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fido.credentialprovider.R
import com.fido.credentialprovider.crossdevice.CrossDeviceConfig
import com.fido.credentialprovider.crossdevice.CrossDeviceDeepLinkParser
import com.fido.credentialprovider.crossdevice.CrossDeviceDenyReporter
import com.fido.credentialprovider.crossdevice.CrossDeviceLoginFlow
import com.fido.credentialprovider.crossdevice.CrossDeviceServerClient
import com.fido.credentialprovider.keystore.LocalCredentialStore
import com.fido.credentialprovider.webauthn.AssertionSigner
import org.json.JSONObject

/**
 * 桌機瀏覽器 QR 掃碼跨裝置登入（情境三）手機端新入口。
 * 對應設計文件 `docs/decisions/qr-cross-device-login-design.md` 第 4 節、
 * `docs/api-contract.md` §3.4、CLAUDE.md「桌機 QR 掃碼跨裝置登入（情境三）決策定案」。
 *
 * **與 [CreatePasskeyActivity] / [GetPasskeyActivity] 的本質差異（設計文件 4.1）**：
 *  - 由誰喚起：本 Activity 由使用者掃 QR 觸發的 **deep link（App Link）** 啟動，不是系統
 *    Credential Manager 以 PendingIntent 隱式啟動。
 *  - 是否有 `CallingAppInfo`：沒有——這不是 Credential Manager 流程，發起端在另一台桌機。
 *  - origin 來源：伺服器依 `xdevId → tenant` **權威給定**（claim 回應的 `rpId`/`origin`），
 *    不經 [com.fido.credentialprovider.webauthn.OriginResolver]（那條路徑在此情境不適用，
 *    見設計文件 4.3）。
 *  - 結果如何回傳：本 App **自己 HTTPS POST** 給 fido-server（[CrossDeviceServerClient]，
 *    全新能力），不經系統 Credential Manager 交還。
 *
 * **硬性範圍邊界（CLAUDE.md「非獨立 APP 跳轉」決策的 carve-out，務必遵守，不得逾越）**：
 *  - 只能經有效的、伺服器發出的 `xdevId` deep link 進入。
 *  - 只做「claim → 確認 → 簽 assertion → submit」單一 ceremony（見 [CrossDeviceLoginFlow]）。
 *  - **絕不**新增裝置列表/撤銷/註冊等任何管理或並行認證 UI。
 *  - 完全不影響 [CreatePasskeyActivity]/[GetPasskeyActivity] 既有同裝置流程（未修改其對外行為，
 *    只從 [GetPasskeyActivity] 抽出的共用邏輯見 [AssertionSigner]/[UserVerificationGate] 檔頭
 *    「重構原則」說明）。
 *
 * **「使用者取消」與「本機無憑證」向伺服器回報放棄（§3.4.E deny 端點 / D18）**：先前版本這兩個
 * 分支只在本機結束流程、不通知伺服器，讓 session 被動逾時成 `EXPIRED`（規格缺口，已回報
 * systems-analyst 並拍板補上 §3.4.E）。現已改為呼叫 [CrossDeviceServerClient.deny]
 * （見 [onCancelClicked]、[onClaimSucceeded] 對應的 `NoCredentialForRp` 分支），
 * 但呼叫方式是 **best-effort**（[CrossDeviceDenyReporter]）——deny 呼叫失敗（網路錯誤、伺服器
 * 錯誤碼）不影響「使用者本來就要取消/沒有憑證」這個已經確定的本機結果，不會卡住使用者或跳出
 * 錯誤畫面，頂多是稽核訊號沒送達。
 *
 * **proximity 警示顯示時機（依實際 api-contract.md 欄位可用性決定，非設計文件字面逐字照搬）**：
 * `docs/decisions/qr-cross-device-login-design.md` 4.2 步驟 4 提到確認畫面「若伺服器回應內容有
 * proximityMismatch…額外顯示警示文字」；但實際核對 `docs/api-contract.md` §3.4.B（claim 回應）
 * 欄位只有 `rpId`/`origin`/`tenantDisplayName`/`challenge`/`verificationCode`/`expiresAt`，
 * **不含** proximity 相關欄位——`proximity.mismatch` 只存在於 §3.4.C（result 回應），也就是
 * 使用者已經簽章送出**之後**才會知道。因此本實作把 proximity 警示顯示在步驟 7 的最終結果畫面
 * （[CrossDeviceLoginFlow.UiState.Confirmed.proximityMismatch]），而非步驟 4 的確認畫面之前——
 * 這是遵循任務指示「結合 api-contract.md 的實際欄位名稱」的結果，此落差已在此註解與任務回報中
 * 明確標示，供 systems-analyst 複核是否需要調整設計文件本身。
 */
class CrossDeviceLoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CrossDeviceLoginActivity"
    }

    private val client = CrossDeviceServerClient(CrossDeviceConfig.FIDO_SERVER_BASE_URL)

    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var credentialSelectionGroup: View
    private lateinit var credentialButtonsContainer: LinearLayout
    private lateinit var confirmationGroup: View
    private lateinit var tenantText: TextView
    private lateinit var codeText: TextView
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cross_device_login)

        statusText = findViewById(R.id.crossDeviceStatusText)
        warningText = findViewById(R.id.crossDeviceWarningText)
        credentialSelectionGroup = findViewById(R.id.credentialSelectionGroup)
        credentialButtonsContainer = findViewById(R.id.credentialButtonsContainer)
        confirmationGroup = findViewById(R.id.confirmationGroup)
        tenantText = findViewById(R.id.crossDeviceTenantText)
        codeText = findViewById(R.id.crossDeviceCodeText)
        confirmButton = findViewById(R.id.crossDeviceConfirmButton)
        cancelButton = findViewById(R.id.crossDeviceCancelButton)

        // 設計文件 4.2 步驟 1：解析 deep link，只取 xdevId、忽略 host
        // （見 CrossDeviceDeepLinkParser 檔頭「設計決定」）。格式不符 → 顯示錯誤並結束、不崩潰。
        when (val parseResult = CrossDeviceDeepLinkParser.parse(intent?.dataString)) {
            is CrossDeviceDeepLinkParser.Result.Invalid -> {
                Log.w(TAG, "deep link 格式不符：${parseResult.reason} data=${intent?.dataString}")
                render(CrossDeviceLoginFlow.onInvalidLink(parseResult.reason))
            }
            is CrossDeviceDeepLinkParser.Result.Valid -> {
                render(CrossDeviceLoginFlow.UiState.Loading)
                claim(parseResult.xdevId)
            }
        }
    }

    /** 設計文件 4.2 步驟 2：向 fido-server claim session，取得權威 rpId/origin/challenge。 */
    private fun claim(xdevId: String) {
        Thread {
            val outcome: Result<CrossDeviceLoginFlow.ClaimContext> = try {
                val httpResult = client.claim(xdevId)
                if (httpResult.statusCode == 200) {
                    parseClaimSuccess(xdevId, httpResult.body)
                } else {
                    Result.failure(IllegalStateException(extractErrorCode(httpResult.body) ?: "HTTP_${httpResult.statusCode}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "claim 端點呼叫失敗：${e.message}", e)
                Result.failure(e)
            }

            outcome.fold(
                onSuccess = { context -> runOnUiThread { onClaimSucceeded(context) } },
                onFailure = { e ->
                    runOnUiThread { render(CrossDeviceLoginFlow.onClaimFailed(e.message ?: "CLAIM_FAILED")) }
                },
            )
        }.start()
    }

    private fun parseClaimSuccess(xdevId: String, body: JSONObject): Result<CrossDeviceLoginFlow.ClaimContext> {
        return try {
            Result.success(
                CrossDeviceLoginFlow.ClaimContext(
                    xdevId = xdevId,
                    rpId = body.getString("rpId"),
                    origin = body.getString("origin"),
                    tenantDisplayName = body.optString("tenantDisplayName", body.getString("rpId")),
                    challengeB64Url = body.getString("challenge"),
                    verificationCode = body.optString("verificationCode", ""),
                ),
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("MALFORMED_CLAIM_RESPONSE: ${e.message}"))
        }
    }

    /**
     * 設計文件 4.2 步驟 3：以 claim 回傳的權威 `rpId` 查本機 [LocalCredentialStore] 內的
     * active 憑證。0 筆 → 「此裝置未註冊」；1 筆 → 直接進確認畫面；多筆 → 顯示選擇器。
     * 0 筆時依 §3.4.E / D18 best-effort 回報 `reason=NO_CREDENTIAL`
     * （見本類別檔頭「使用者取消/本機無憑證」說明）。
     */
    private fun onClaimSucceeded(context: CrossDeviceLoginFlow.ClaimContext) {
        val credentialIds = LocalCredentialStore.listCredentialIds(this, context.rpId)
        val state = CrossDeviceLoginFlow.onClaimSucceeded(context, credentialIds)
        CrossDeviceLoginFlow.denyReasonFor(state)?.let { reason -> fireDenyBestEffort(context.xdevId, reason) }
        render(state)
    }

    private fun onCredentialChosen(context: CrossDeviceLoginFlow.ClaimContext, credentialId: String) {
        render(CrossDeviceLoginFlow.onCredentialChosen(context, credentialId))
    }

    /**
     * 設計文件 4.2 步驟 4 取消分支。依 §3.4.E / D18 best-effort 回報 `reason=USER_CANCELLED`
     * （見本類別檔頭「使用者取消/本機無憑證」說明），再結束畫面。
     */
    private fun onCancelClicked(context: CrossDeviceLoginFlow.ClaimContext) {
        fireDenyBestEffort(context.xdevId, CrossDeviceServerClient.DenyReason.USER_CANCELLED)
        render(CrossDeviceLoginFlow.onUserCancelled())
    }

    /**
     * best-effort 呼叫 §3.4.E deny 端點（[CrossDeviceDenyReporter]）：在背景執行緒發出，
     * **不等待**回應即可讓呼叫端接著結束畫面/轉換狀態——deny 只是稽核訊號，成功與否都不影響
     * 使用者已確定的取消/無憑證結果，因此刻意不做同步等待、不因逾時或錯誤卡住 UI。
     */
    private fun fireDenyBestEffort(xdevId: String, reason: CrossDeviceServerClient.DenyReason) {
        Thread {
            CrossDeviceDenyReporter.reportBestEffort(
                sender = { id, r ->
                    val result = client.deny(id, r)
                    Log.i(TAG, "deny 呼叫完成（best-effort）xdevId=$id reason=$r status=${result.statusCode}")
                },
                xdevId = xdevId,
                reason = reason,
                onFailure = { e -> Log.w(TAG, "deny 呼叫失敗（best-effort，不影響本機流程）：${e.message}") },
            )
        }.start()
    }

    /** 設計文件 4.2 步驟 4 確認分支 → 步驟 5 UV 閘門。 */
    private fun onConfirmClicked(context: CrossDeviceLoginFlow.ClaimContext, credentialId: String) {
        render(CrossDeviceLoginFlow.onUserConfirmed())
        // 步驟 5：UV 閘門——重用 GetPasskeyActivity.requireUserVerification 的邏輯
        // （見 UserVerificationGate 檔頭「重構原則」說明）。
        UserVerificationGate.require(
            activity = this,
            title = "確認跨裝置登入",
            onSuccess = { signAndSubmit(context, credentialId) },
            onFailure = { reason ->
                render(CrossDeviceLoginFlow.onSubmitFailed("USER_VERIFICATION_FAILED: $reason"))
            },
        )
    }

    /**
     * 設計文件 4.2 步驟 6–7：重用 [AssertionSigner]（= `GetPasskeyActivity.performAssertion`
     * 的簽章核心）簽出 assertion，送到 §3.4.C result 端點。`callerSuppliedClientDataHash` 傳
     * null——cross-device 情境沒有瀏覽器 client 提供 hash，一律走自建 clientDataJSON 重新雜湊的
     * 路徑（設計文件 4.2 步驟 6 明文指出的分支）。
     */
    private fun signAndSubmit(context: CrossDeviceLoginFlow.ClaimContext, credentialId: String) {
        Thread {
            try {
                val signed = AssertionSigner.sign(
                    context = this,
                    credentialIdB64Url = credentialId,
                    rpId = context.rpId,
                    challengeB64Url = context.challengeB64Url,
                    origin = context.origin,
                    callerSuppliedClientDataHash = null,
                )
                Log.i(
                    TAG,
                    "cross-device assertion 簽章完成：credentialId=$credentialId signCount=${signed.newSignCount}",
                )

                val submitResult = client.submitResult(context.xdevId, JSONObject(signed.assertionResponseJson))
                if (submitResult.statusCode == 200 && submitResult.body.optString("status") == "CONFIRMED") {
                    val proximityMismatch = submitResult.body
                        .optJSONObject("proximity")
                        ?.optBoolean("mismatch", false)
                        ?: false
                    runOnUiThread { render(CrossDeviceLoginFlow.onSubmitSucceeded(proximityMismatch)) }
                } else {
                    val reason = extractErrorCode(submitResult.body) ?: "HTTP_${submitResult.statusCode}"
                    runOnUiThread { render(CrossDeviceLoginFlow.onSubmitFailed(reason)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cross-device 簽章或送出 result 失敗：${e.message}", e)
                runOnUiThread { render(CrossDeviceLoginFlow.onSubmitFailed(e.message ?: "SUBMIT_FAILED")) }
            }
        }.start()
    }

    private fun extractErrorCode(body: JSONObject): String? =
        body.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }

    private fun render(state: CrossDeviceLoginFlow.UiState) {
        credentialSelectionGroup.visibility = View.GONE
        confirmationGroup.visibility = View.GONE
        warningText.visibility = View.GONE
        credentialButtonsContainer.removeAllViews()

        when (state) {
            is CrossDeviceLoginFlow.UiState.Loading -> {
                statusText.text = "處理中，請稍候…"
            }

            is CrossDeviceLoginFlow.UiState.InvalidLink -> {
                statusText.text = "此連結無效（${state.reason}），請回到電腦重新產生 QR code。"
            }

            is CrossDeviceLoginFlow.UiState.ClaimFailed -> {
                statusText.text = "無法取得登入請求（${state.reason}），請回到電腦重新產生 QR code。"
            }

            is CrossDeviceLoginFlow.UiState.NoCredentialForRp -> {
                statusText.text =
                    "此裝置尚未註冊「${state.tenantDisplayName}」（${state.rpId}）的 FIDO 憑證，" +
                        "無法用於此次登入。請改用已註冊該網站的裝置掃碼，或於該裝置上先完成註冊。"
            }

            is CrossDeviceLoginFlow.UiState.SelectingCredential -> {
                statusText.text = "請選擇要用於此次登入的憑證："
                credentialSelectionGroup.visibility = View.VISIBLE
                state.credentialIds.forEachIndexed { index, credentialId ->
                    val button = Button(this).apply {
                        text = "使用憑證 ${index + 1}"
                        setOnClickListener { onCredentialChosen(state.claim, credentialId) }
                    }
                    credentialButtonsContainer.addView(button)
                }
            }

            is CrossDeviceLoginFlow.UiState.AwaitingConfirmation -> {
                statusText.text = "已收到來自電腦端的登入請求"
                confirmationGroup.visibility = View.VISIBLE
                tenantText.text = "網站：${state.claim.tenantDisplayName}（${state.claim.rpId}）"
                codeText.text = "電腦端確認碼：${state.claim.verificationCode}"
                confirmButton.setOnClickListener { onConfirmClicked(state.claim, state.credentialId) }
                cancelButton.setOnClickListener { onCancelClicked(state.claim) }
            }

            is CrossDeviceLoginFlow.UiState.Denied -> {
                statusText.text = "已取消。如非本人操作，無需進一步處理；如為本人操作，請回到電腦重新產生 QR code。"
            }

            is CrossDeviceLoginFlow.UiState.SigningAndSubmitting -> {
                statusText.text = "驗證中，請稍候…"
            }

            is CrossDeviceLoginFlow.UiState.Confirmed -> {
                statusText.text = "已確認，請回到電腦繼續。"
                if (state.proximityMismatch) {
                    warningText.visibility = View.VISIBLE
                    warningText.text = "提醒：偵測到此次登入位置與電腦端不一致，請確認這是否為本人操作。"
                    Toast.makeText(this, warningText.text, Toast.LENGTH_LONG).show()
                }
            }

            is CrossDeviceLoginFlow.UiState.SubmitFailed -> {
                statusText.text = "登入確認失敗（${state.reason}），請回到電腦重新操作。"
            }
        }
    }
}
