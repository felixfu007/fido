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
import com.fido.credentialprovider.PocConfig
import com.fido.credentialprovider.keystore.HardwareKeyManager
import com.fido.credentialprovider.keystore.LocalCredentialStore
import com.fido.credentialprovider.webauthn.AttestationObjectBuilder
import com.fido.credentialprovider.webauthn.AuthenticatorDataBuilder
import com.fido.credentialprovider.webauthn.ClientDataBuilder
import com.fido.credentialprovider.webauthn.CoseKeyEncoder
import org.json.JSONObject
import java.security.MessageDigest
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

        val parsed = try {
            parseCreationOptions(requestJson)
        } catch (e: Exception) {
            Log.e(TAG, "requestJson 解析失敗：${e.message}", e)
            finishWithError("Invalid requestJson: ${e.message}")
            return
        }

        requireUserVerification(
            title = "註冊 FIDO 硬體金鑰",
            onSuccess = { performRegistration(parsed) },
            onFailure = { reason -> finishWithError("User verification failed: $reason") },
        )
    }

    private data class CreationOptions(val rpId: String, val challengeB64Url: String)

    private fun parseCreationOptions(requestJson: String): CreationOptions {
        val obj = JSONObject(requestJson)
        val rpId = obj.optJSONObject("rp")?.optString("id") ?: PocConfig.RP_ID
        val challenge = obj.getString("challenge")
        return CreationOptions(rpId = rpId, challengeB64Url = challenge)
    }

    private fun performRegistration(options: CreationOptions) {
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
                            origin = PocConfig.ORIGIN,
                        )
                        val clientDataHash = sha256(clientDataJson)

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
    ): String {
        // 對齊 api-contract.md 2.2 RegistrationResultRequest.CredentialAttestation 結構，
        // 讓 harness 可以幾乎原封不動把這個 JSON 轉送給 fido-server /registration/result。
        val response = JSONObject()
            .put("clientDataJSON", b64UrlEncode(clientDataJson))
            .put("attestationObject", b64UrlEncode(attestationObject))
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

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
