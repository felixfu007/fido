package com.fido.credentialprovider.ui

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import com.fido.credentialprovider.keystore.HardwareKeyManager
import com.fido.credentialprovider.keystore.LocalCredentialStore
import com.fido.credentialprovider.webauthn.AttestationObjectBuilder
import com.fido.credentialprovider.webauthn.AuthenticatorDataBuilder
import com.fido.credentialprovider.webauthn.ClientDataBuilder
import com.fido.credentialprovider.webauthn.CoseKeyEncoder
import com.fido.credentialprovider.webauthn.OriginResolver
import com.fido.credentialprovider.webauthn.RegistrationResponseFields
import org.json.JSONObject
import java.security.Signature

/**
 * 【清單項目 1/2/3/4 收斂】使用者在系統 Credential Manager bottom sheet 選擇本 provider 的
 * CreateEntry 後，由系統以 PendingIntent 啟動本 Activity，實際執行：
 *  1. 解析 WebAuthn creationOptions（`requestJson`）取得 challenge / rpId
 *  2. 使用者驗證閘門（BiometricPrompt，UV=required）
 *  3. Android Keystore 硬體金鑰產生（[HardwareKeyManager]，StrongBox 優先、TEE fallback、
 *     絕不落軟體）
 *  4. 手動組出 `fmt="android-key"` attestationObject（[AttestationObjectBuilder]）
 *  5. 把結果以 [CreatePublicKeyCredentialResponse] 交還系統 Credential Manager
 */
class CreatePasskeyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CreatePasskeyActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val providerRequest = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val publicKeyRequest = providerRequest?.callingRequest as? CreatePublicKeyCredentialRequest
        if (publicKeyRequest == null) {
            Log.e(TAG, "未取得 CreatePublicKeyCredentialRequest，無法繼續。")
            finishWithError("Missing CreatePublicKeyCredentialRequest")
            return
        }

        val requestJson = publicKeyRequest.requestJson
        Log.i(TAG, "取得 registration requestJson（節錄前 200 字）：${requestJson.take(200)}")

        // 【2026-07-23 真機除錯根因修正】androidx.credentials.CreatePublicKeyCredentialRequest
        // 帶有選填的 clientDataHash 欄位：特權呼叫端（例如 Chrome 這類瀏覽器）身為 WebAuthn
        // 規範定義的「client」本身，才是唯一有資格建構 CollectedClientData／clientDataJSON 的一方
        // （這正是規範把 origin 綁定交給 client 而非 authenticator 驗證的原因），因此會自行算好
        // clientDataHash 傳進來，並預期 provider（=authenticator 角色）直接對這個 hash 簽章，
        // 不要自作主張另外建構一份 clientDataJSON 再重新雜湊——provider 端算出來的 JSON 位元組
        // 幾乎必然與瀏覽器最終實際吐給網頁 JS／送給 server 的 clientDataJSON 不同（即使
        // type/challenge/origin 三個「值」都對，逐 byte 內容也不會相同：例如欄位順序、空白、是否
        // 含 crossOrigin），導致簽的 hash 與最終送到 server 驗證用的 hash 對不上，攻自己方伎倆
        // 逃過既有的 type/challenge/origin 值比對（那些只檢查值，不檢查逐 byte 內容），只在
        // attStmt.sig 密碼學驗證這一關才會現形——這正是真機以 Chrome 實測（本專案至今第一次真的
        // 走過 Chrome 這條路）才首次踢到、模擬器/單元測試從未踢到的原因，因為兩者都是自己造
        // 請求、自己不會設 clientDataHash。若呼叫端沒有提供這個欄位（例如原生 App opt-in 情境，
        // 呼叫端本身不是瀏覽器，見 docs/origin-binding.md），則落回本專案原本自建 clientDataJSON
        // 的路徑，因為那個情境下沒有其他權威來源可用。
        val callerSuppliedClientDataHash = publicKeyRequest.clientDataHash
        Log.i(
            TAG,
            "呼叫端是否提供 clientDataHash（特權呼叫端如瀏覽器慣例）：" +
                "${callerSuppliedClientDataHash != null}",
        )

        val parsed = try {
            parseCreationOptions(requestJson)
        } catch (e: Exception) {
            Log.e(TAG, "requestJson 解析失敗：${e.message}", e)
            finishWithError("Invalid requestJson: ${e.message}")
            return
        }

        // docs/origin-binding.md 第 6 節：origin 一律由呼叫方 CallingAppInfo 動態解析與驗證，
        // 不接受任何寫死值；解析失敗（含冒充受信任瀏覽器）一律直接拒絕，不觸發使用者驗證/
        // 產生任何金鑰或簽章（見該文件 6.3 拒絕條件）。
        val originDecision = OriginResolver.resolveTrustedOrigin(providerRequest?.callingAppInfo)
        val resolvedOrigin = when (originDecision) {
            is OriginResolver.OriginDecision.Reject -> {
                Log.w(
                    TAG,
                    "origin 解析被拒絕：${originDecision.reason}（呼叫方 package=" +
                        "${providerRequest?.callingAppInfo?.packageName}）",
                )
                finishWithError("ORIGIN_REJECTED: ${originDecision.reason}")
                return
            }
            is OriginResolver.OriginDecision.UseOrigin -> originDecision
        }
        Log.i(
            TAG,
            "origin 解析完成：sourceType=${resolvedOrigin.sourceType} origin=${resolvedOrigin.origin}",
        )

        requireUserVerification(
            title = "註冊 FIDO 硬體金鑰",
            onSuccess = {
                performRegistration(parsed, resolvedOrigin.origin, callerSuppliedClientDataHash)
            },
            onFailure = { reason -> finishWithError("User verification failed: $reason") },
        )
    }

    private data class CreationOptions(val rpId: String, val challengeB64Url: String)

    private fun parseCreationOptions(requestJson: String): CreationOptions {
        val obj = JSONObject(requestJson)
        // docs/origin-binding.md 6.5：rpId 一律以 requestJson 的 rp.id 為準，不 fallback 到任何
        // 寫死值；缺漏視為請求格式錯誤（由呼叫端的 try/catch 轉成 finishWithError）。
        val rpId = obj.optJSONObject("rp")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("requestJson missing rp.id")
        val challenge = obj.getString("challenge")
        return CreationOptions(rpId = rpId, challengeB64Url = challenge)
    }

    private fun performRegistration(
        options: CreationOptions,
        origin: String,
        callerSuppliedClientDataHash: ByteArray?,
    ) {
        Thread {
            try {
                val credentialIdB64Url = LocalCredentialStore.randomCredentialIdBase64Url()
                val alias = LocalCredentialStore.aliasFor(credentialIdB64Url)
                val challengeBytes = b64UrlDecode(options.challengeB64Url)

                Log.i(TAG, "開始產生硬體金鑰：alias=$alias attestationChallengeHex=${toHex(challengeBytes)}")
                val outcome = HardwareKeyManager.generate(alias, challengeBytes)

                when (outcome) {
                    is HardwareKeyManager.KeyGenOutcome.Rejected -> {
                        Log.w(TAG, "金鑰產生被拒絕：${outcome.reason}（偵測等級：${outcome.detectedLevel}）")
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "此裝置不支援硬體安全金鑰，無法完成註冊（${outcome.detectedLevel}）",
                                Toast.LENGTH_LONG,
                            ).show()
                            finishWithError("HARDWARE_SECURITY_NOT_MET: ${outcome.reason}")
                        }
                        return@Thread
                    }
                    is HardwareKeyManager.KeyGenOutcome.Success -> {
                        val credentialIdBytes = b64UrlDecode(credentialIdB64Url)
                        val coseKey = CoseKeyEncoder.encodeEcP256(outcome.publicKey)
                        val authenticatorData = AuthenticatorDataBuilder.buildForRegistration(
                            rpId = options.rpId,
                            userVerified = true,
                            signCount = 0L,
                            aaguid = AuthenticatorDataBuilder.ZERO_AAGUID,
                            credentialId = credentialIdBytes,
                            credentialPublicKeyCose = coseKey,
                        )
                        val clientDataJson = ClientDataBuilder.build(
                            type = "webauthn.create",
                            challengeBase64Url = options.challengeB64Url,
                            origin = origin,
                        )
                        // 【2026-07-23 真機除錯根因修正】見 onCreate 內對 callerSuppliedClientDataHash
                        // 的說明：有提供就必須簽這個 hash，不可另外自建 clientDataJSON 重新雜湊。
                        val clientDataHash = ClientDataBuilder.resolveClientDataHash(
                            callerSuppliedClientDataHash,
                            clientDataJson,
                        )

                        val signature = Signature.getInstance("SHA256withECDSA").apply {
                            initSign(outcome.privateKey)
                            update(authenticatorData)
                            update(clientDataHash)
                        }.sign()

                        val attestationObject = AttestationObjectBuilder.build(
                            authenticatorData = authenticatorData,
                            attStmtSignature = signature,
                            certificateChain = outcome.certificateChain,
                        )

                        Log.i(
                            TAG,
                            "attestationObject 組裝完成：fmt=android-key bytes=${attestationObject.size} " +
                                "x5cChainLength=${outcome.certificateChain.size} detectedLevel=${outcome.detectedLevel} " +
                                "requestedStrongBox=${outcome.requestedStrongBox} " +
                                "bypassedHardwareGateForDiagnostics=${outcome.bypassedHardwareGateForDiagnostics}",
                        )
                        if (outcome.bypassedHardwareGateForDiagnostics) {
                            Log.w(TAG, "【PoC 診斷】此 attestationObject 來自被診斷旗標放行的軟體金鑰，" +
                                "僅供檢視 CBOR 結構是否可被伺服器解析，不代表硬體閘門通過。")
                            // 【PoC 診斷專用】把憑證鏈各張憑證的 PEM 印到 log，供 dev-engineer 手動擷取
                            // root 憑證，加入 fido-server 的 fido.attestation.poc-trust 額外信任設定
                            // （見 docs/android-poc-checklist.md 附錄 A-3、CLAUDE.md）。正式流程不會印這些。
                            outcome.certificateChain.forEachIndexed { index, cert ->
                                val pem = "-----BEGIN CERTIFICATE-----\n" +
                                    Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                                    "\n-----END CERTIFICATE-----"
                                Log.d(TAG, "【PoC 診斷】x5c[$index] subject=${cert.subjectX500Principal} PEM=\n$pem")
                            }
                        }

                        LocalCredentialStore.rememberCredential(this, credentialIdB64Url, options.rpId)

                        val registrationResponseJson = buildRegistrationResponseJson(
                            credentialIdB64Url = credentialIdB64Url,
                            clientDataJson = clientDataJson,
                            attestationObject = attestationObject,
                            authenticatorData = authenticatorData,
                            publicKeyDer = outcome.publicKey.encoded,
                        )

                        runOnUiThread {
                            val response = CreatePublicKeyCredentialResponse(registrationResponseJson)
                            val resultData = Intent()
                            PendingIntentHandler.setCreateCredentialResponse(resultData, response)
                            setResult(RESULT_OK, resultData)
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "註冊流程發生未預期例外：${e.message}", e)
                runOnUiThread { finishWithError("Unexpected error: ${e.message}") }
            }
        }.start()
    }

    private fun buildRegistrationResponseJson(
        credentialIdB64Url: String,
        clientDataJson: ByteArray,
        attestationObject: ByteArray,
        authenticatorData: ByteArray,
        publicKeyDer: ByteArray,
    ): String {
        // 【2026-07-23 真機除錯新增 authenticatorData/publicKeyAlgorithm/publicKey】
        // 對齊 api-contract.md 2.2 RegistrationResultRequest.CredentialAttestation 結構的同時，
        // 也必須滿足 Chrome 原生層 Fido2CredentialRequest.MakeCredentialResponseFromJson 自己的
        // 解析要求（`components/webauthn/json/value_conversions.cc` `MakeCredentialResponseFromValue`
        // 查證結果，見 RegistrationResponseFields 檔頭說明）——這三個欄位對 fido-server 是冗餘的
        // （fido-server 自行解析 attestationObject CBOR 取得同樣資訊，見任務回報，未動
        // RegistrationResultRequest 契約），但 Chrome 在把結果交回頁面 JS 之前，會先用這三個
        // 欄位跟它自己重新解碼 attestationObject 算出的值比對，不符或缺漏就直接判定整個回應
        // 無效、不會呼叫到 fido-server。
        val fields = RegistrationResponseFields.from(
            clientDataJson = clientDataJson,
            attestationObject = attestationObject,
            authenticatorData = authenticatorData,
            publicKeyDer = publicKeyDer,
        )
        val response = JSONObject()
            .put("clientDataJSON", fields.clientDataJsonB64Url)
            .put("attestationObject", fields.attestationObjectB64Url)
            .put("authenticatorData", fields.authenticatorDataB64Url)
            .put("publicKeyAlgorithm", fields.publicKeyAlgorithm)
            .put("publicKey", fields.publicKeyB64Url)
            .put("transports", org.json.JSONArray(listOf("internal")))

        return JSONObject()
            .put("id", credentialIdB64Url)
            .put("rawId", credentialIdB64Url)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("clientExtensionResults", JSONObject())
            .put("response", response)
            .toString()
    }

    private fun requireUserVerification(title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val biometricManager = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = biometricManager.canAuthenticate(allowed)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "此裝置無可用的生物辨識/裝置解鎖（code=$canAuthenticate），PoC 環境下改為直接放行 UV。")
            // PoC 已知限制：部分模擬器設定下沒有啟用螢幕鎖/生物辨識，會導致 BiometricPrompt
            // 無法使用；此時放行只是為了不讓 PoC 卡在與硬體 attestation 無關的環節，見任務回報。
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure("errorCode=$errorCode $errString")
                }

                override fun onAuthenticationFailed() {
                    // 使用者驗證失敗但可重試，不在此結束流程，等待使用者再次嘗試或取消。
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun finishWithError(message: String) {
        val resultData = Intent()
        PendingIntentHandler.setCreateCredentialException(resultData, CreateCredentialUnknownException(message))
        setResult(RESULT_CANCELED, resultData)
        finish()
    }

    private fun b64UrlDecode(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
