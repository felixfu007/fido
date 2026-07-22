package com.fido.credentialprovider.harness

import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.fido.credentialprovider.PocConfig
import com.fido.credentialprovider.R
import com.fido.credentialprovider.keystore.HardwareKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 【PoC 專用測試 harness，非產品程式碼】模擬「購物網站前端 + 後端」，觸發
 * `CredentialManager.createCredential()/getCredential()`（交給系統 Credential Manager 決定
 * 路由到 [com.fido.credentialprovider.FidoCredentialProviderService]），並直接呼叫
 * fido-server REST 端點驗證/落庫結果。見 [FidoServerClient] 開頭說明與
 * docs/android-poc-checklist.md 附錄 A-2。
 */
class HarnessActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var serverBaseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var externalUserIdInput: EditText
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_harness)

        credentialManager = CredentialManager.create(this)
        logView = findViewById(R.id.logText)
        serverBaseUrlInput = findViewById(R.id.serverBaseUrlInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        externalUserIdInput = findViewById(R.id.externalUserIdInput)

        findViewById<Button>(R.id.registerButton).setOnClickListener { runRegistration() }
        findViewById<Button>(R.id.loginButton).setOnClickListener { runLogin() }
        findViewById<Button>(R.id.listDevicesButton).setOnClickListener { runListDevices() }
        findViewById<Button>(R.id.expiredChallengeButton).setOnClickListener { runExpiredChallengeTest() }
        findViewById<Button>(R.id.diagnosticsToggleButton).setOnClickListener { toggleDiagnosticsMode() }
    }

    /**
     * 【診斷用，非清單項目 2 的通過判定】切換 [HardwareKeyManager.diagnosticsAllowSoftwareKeyForPoCInspection]。
     * 開啟後下一次註冊會放行模擬器的軟體等級金鑰，僅用來取得真實 Android Keystore 簽發的
     * attestation 憑證鏈，驗證清單項目 3（android-key CBOR 結構）在真實裝置輸出下是否可解析；
     * 不代表、也不影響清單項目 2 的硬體閘門通過判定（該判定以此旗標關閉時的行為為準）。
     */
    private fun toggleDiagnosticsMode() {
        HardwareKeyManager.diagnosticsAllowSoftwareKeyForPoCInspection =
            !HardwareKeyManager.diagnosticsAllowSoftwareKeyForPoCInspection
        val state = HardwareKeyManager.diagnosticsAllowSoftwareKeyForPoCInspection
        log("【診斷旗標】diagnosticsAllowSoftwareKeyForPoCInspection = $state" +
            if (state) "（下一次註冊會放行軟體金鑰，僅供檢視真實 attestationObject，不代表硬體閘門通過）" else "")
        Toast.makeText(this, "診斷旗標：$state", Toast.LENGTH_SHORT).show()
    }

    private fun client(): FidoServerClient =
        FidoServerClient(serverBaseUrlInput.text.toString().trim(), apiKeyInput.text.toString().trim())

    private fun externalUserId(): String = externalUserIdInput.text.toString().trim()

    /** [FidoServerClient] 用同步 [java.net.HttpURLConnection]，一律切到 IO dispatcher 執行，避免 NetworkOnMainThreadException。 */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun log(msg: String) {
        android.util.Log.i("HarnessActivity", msg)
        runOnUiThread {
            logView.text = "${logView.text}\n$msg"
        }
    }

    private fun runRegistration() {
        lifecycleScope.launch {
            try {
                log("== 註冊開始 ==")
                val optionsResult = io { client().registrationOptions(externalUserId(), "PoC User", "PoC Emulator Device") }
                log("registration/options -> HTTP ${optionsResult.statusCode}")
                if (optionsResult.statusCode != 200) {
                    log("失敗：${optionsResult.body}")
                    return@launch
                }
                val ceremonyId = optionsResult.body.getString("ceremonyId")
                val publicKey = optionsResult.body.getJSONObject("publicKey")
                log("ceremonyId=$ceremonyId challenge=${publicKey.getString("challenge")}")

                val request = CreatePublicKeyCredentialRequest(requestJson = publicKey.toString())
                val response: CreateCredentialResponse = try {
                    credentialManager.createCredential(this@HarnessActivity, request)
                } catch (e: CreateCredentialException) {
                    log("createCredential 失敗：${e.javaClass.simpleName} ${e.message}")
                    return@launch
                }
                val pkResponse = response as? CreatePublicKeyCredentialResponse
                if (pkResponse == null) {
                    log("非預期的回應型別：${response.javaClass.name}")
                    return@launch
                }
                log("createCredential 成功，registrationResponseJson 節錄：${pkResponse.registrationResponseJson.take(200)}")

                val credentialJson = JSONObject(pkResponse.registrationResponseJson)
                val resultResp = io {
                    client().registrationResult(ceremonyId, externalUserId(), credentialJson, "PoC Emulator Device")
                }
                log("registration/result -> HTTP ${resultResp.statusCode}")
                log("body=${resultResp.body}")
            } catch (e: Exception) {
                log("例外：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun runLogin() {
        lifecycleScope.launch {
            try {
                log("== 登入開始 ==")
                val optionsResult = io { client().authenticationOptions(externalUserId()) }
                log("authentication/options -> HTTP ${optionsResult.statusCode}")
                if (optionsResult.statusCode != 200) {
                    log("失敗：${optionsResult.body}")
                    return@launch
                }
                val ceremonyId = optionsResult.body.getString("ceremonyId")
                val publicKey = optionsResult.body.getJSONObject("publicKey")
                val allowCredentials = publicKey.optJSONArray("allowCredentials")
                log("ceremonyId=$ceremonyId allowCredentialsCount=${allowCredentials?.length() ?: 0}")
                if (allowCredentials == null || allowCredentials.length() == 0) {
                    log("此使用者尚無已註冊裝置（allowCredentials 為空），請先執行註冊。")
                    return@launch
                }

                val option = GetPublicKeyCredentialOption(requestJson = publicKey.toString())
                val getRequest = GetCredentialRequest(listOf(option))
                val result = try {
                    credentialManager.getCredential(this@HarnessActivity, getRequest)
                } catch (e: GetCredentialException) {
                    log("getCredential 失敗：${e.javaClass.simpleName} ${e.message}")
                    return@launch
                }
                val pkCredential = result.credential as? PublicKeyCredential
                if (pkCredential == null) {
                    log("非預期的憑證型別：${result.credential.javaClass.name}")
                    return@launch
                }
                log("getCredential 成功，authenticationResponseJson 節錄：${pkCredential.authenticationResponseJson.take(200)}")

                val credentialJson = JSONObject(pkCredential.authenticationResponseJson)
                val resultResp = io { client().authenticationResult(ceremonyId, credentialJson) }
                log("authentication/result -> HTTP ${resultResp.statusCode}")
                log("body=${resultResp.body}")
            } catch (e: Exception) {
                log("例外：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun runListDevices() {
        lifecycleScope.launch {
            try {
                log("== 列出裝置 ==")
                val resp = io { client().listDevices(externalUserId()) }
                log("devices -> HTTP ${resp.statusCode}")
                log("body=${resp.body}")
                val statusResp = io { client().fidoStatus(externalUserId()) }
                log("fido-status -> HTTP ${statusResp.statusCode} body=${statusResp.body}")
            } catch (e: Exception) {
                log("例外：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /**
     * 清單項目 8：取得 registration options 後，刻意等待超過 60 秒（伺服器
     * `auth_challenges.expires_at` 時效）再送出 result，驗證伺服器回 400 CHALLENGE_EXPIRED；
     * 接著模擬前端「自動重新申請」——重新呼叫 options 並完成一次真正的 ceremony，證明重試路徑
     * 可行、App 不會卡死。
     */
    private fun runExpiredChallengeTest() {
        lifecycleScope.launch {
            try {
                log("== Challenge 60 秒逾時測試開始（會等待 61 秒，請耐心等候）==")
                val optionsResult = io { client().registrationOptions(externalUserId(), "PoC User", "Expiry Test Device") }
                if (optionsResult.statusCode != 200) {
                    log("registration/options 失敗：${optionsResult.body}")
                    return@launch
                }
                val ceremonyId = optionsResult.body.getString("ceremonyId")
                log("取得 ceremonyId=$ceremonyId，開始等待 61 秒...")
                Toast.makeText(this@HarnessActivity, "等待 61 秒以觸發 challenge 逾時...", Toast.LENGTH_LONG).show()
                delay(61_000)

                val dummyCredential = JSONObject()
                    .put("id", "dummy-id")
                    .put("rawId", "dummy-id")
                    .put("type", "public-key")
                    .put(
                        "response",
                        JSONObject()
                            .put("clientDataJSON", Base64.encodeToString("{}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                            .put("attestationObject", Base64.encodeToString(byteArrayOf(0), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)),
                    )
                val expiredResp = io { client().registrationResult(ceremonyId, externalUserId(), dummyCredential, null) }
                log("逾時後送出 result -> HTTP ${expiredResp.statusCode} body=${expiredResp.body}")
                val expiredAsExpected = expiredResp.statusCode == 400 &&
                    expiredResp.body.optJSONObject("error")?.optString("code") == "CHALLENGE_EXPIRED"
                log("是否為預期的 CHALLENGE_EXPIRED：$expiredAsExpected")

                log("模擬前端自動重新申請 options...")
                val retryOptions = io { client().registrationOptions(externalUserId(), "PoC User", "Expiry Test Device Retry") }
                if (retryOptions.statusCode != 200) {
                    log("重新申請 options 失敗：${retryOptions.body}")
                    return@launch
                }
                val retryCeremonyId = retryOptions.body.getString("ceremonyId")
                val retryPublicKey = retryOptions.body.getJSONObject("publicKey")
                log("重新取得 ceremonyId=$retryCeremonyId，改用真正的 CredentialManager 完成一次 ceremony 以證明可正常重跑...")

                val request = CreatePublicKeyCredentialRequest(requestJson = retryPublicKey.toString())
                val response = try {
                    credentialManager.createCredential(this@HarnessActivity, request)
                } catch (e: CreateCredentialException) {
                    log("重試 createCredential 失敗：${e.javaClass.simpleName} ${e.message}")
                    return@launch
                }
                val pkResponse = response as? CreatePublicKeyCredentialResponse ?: return@launch
                val credentialJson = JSONObject(pkResponse.registrationResponseJson)
                val finalResp = io { client().registrationResult(retryCeremonyId, externalUserId(), credentialJson, "Expiry Retry Device") }
                log("重試後 registration/result -> HTTP ${finalResp.statusCode} body=${finalResp.body}")
                log("== Challenge 逾時 + 自動重試測試結束 ==")
            } catch (e: Exception) {
                log("例外：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}
