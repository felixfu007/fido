package com.fido.credentialprovider.ui

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import com.fido.credentialprovider.PocConfig
import com.fido.credentialprovider.keystore.LocalCredentialStore
import com.fido.credentialprovider.webauthn.AuthenticatorDataBuilder
import com.fido.credentialprovider.webauthn.ClientDataBuilder
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/**
 * 【清單項目 6】使用者在系統 Credential Manager bottom sheet 選擇某個本機 passkey entry 後，
 * 由系統以 PendingIntent 啟動本 Activity，實際執行：
 *  1. 解析 WebAuthn getOptions（`requestJson`）取得 challenge / rpId
 *  2. 使用者驗證閘門（BiometricPrompt，UV=required）
 *  3. 用該 credential 對應的 Keystore 私鑰簽 assertion（authenticatorData || clientDataHash）
 *  4. sign counter 遞增（本機維護，見 [LocalCredentialStore]）
 *  5. 把結果以 [GetCredentialResponse] 交還系統 Credential Manager
 */
class GetPasskeyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GetPasskeyActivity"
        const val EXTRA_CREDENTIAL_ID = "com.fido.credentialprovider.EXTRA_CREDENTIAL_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val credentialIdB64Url = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
        if (credentialIdB64Url == null) {
            Log.e(TAG, "缺少 EXTRA_CREDENTIAL_ID，無法判斷使用者選擇了哪個 passkey。")
            finishWithError("Missing credential id extra")
            return
        }

        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        val publicKeyOption = providerRequest?.credentialOptions
            ?.filterIsInstance<GetPublicKeyCredentialOption>()
            ?.firstOrNull()
        if (publicKeyOption == null) {
            Log.e(TAG, "未取得 GetPublicKeyCredentialOption，無法繼續。")
            finishWithError("Missing GetPublicKeyCredentialOption")
            return
        }

        val challengeB64Url = try {
            JSONObject(publicKeyOption.requestJson).getString("challenge")
        } catch (e: Exception) {
            Log.e(TAG, "getOptions requestJson 解析失敗：${e.message}", e)
            finishWithError("Invalid requestJson: ${e.message}")
            return
        }

        val rpId = LocalCredentialStore.getRpId(this, credentialIdB64Url) ?: PocConfig.RP_ID

        requireUserVerification(
            title = "使用 FIDO 硬體金鑰登入",
            onSuccess = { performAssertion(credentialIdB64Url, rpId, challengeB64Url) },
            onFailure = { reason -> finishWithError("User verification failed: $reason") },
        )
    }

    private fun performAssertion(credentialIdB64Url: String, rpId: String, challengeB64Url: String) {
        Thread {
            try {
                val alias = LocalCredentialStore.aliasFor(credentialIdB64Url)
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!keyStore.containsAlias(alias)) {
                    Log.e(TAG, "Keystore 找不到別名 $alias（可能已被系統或使用者清除）")
                    runOnUiThread { finishWithError("Key not found for this credential") }
                    return@Thread
                }
                val privateKey = keyStore.getKey(alias, null) as PrivateKey

                // sign counter：本機維護，見 LocalCredentialStore 說明；每次成功簽章遞增 1。
                val newCount = LocalCredentialStore.getSignCount(this, credentialIdB64Url) + 1

                val authenticatorData = AuthenticatorDataBuilder.buildForAssertion(
                    rpId = rpId,
                    userVerified = true,
                    signCount = newCount,
                )
                val clientDataJson = ClientDataBuilder.build(
                    type = "webauthn.get",
                    challengeBase64Url = challengeB64Url,
                    origin = PocConfig.ORIGIN,
                )
                val clientDataHash = sha256(clientDataJson)

                val signature = Signature.getInstance("SHA256withECDSA").apply {
                    initSign(privateKey)
                    update(authenticatorData)
                    update(clientDataHash)
                }.sign()

                LocalCredentialStore.setSignCount(this, credentialIdB64Url, newCount)

                Log.i(TAG, "assertion 簽章完成：credentialId=$credentialIdB64Url signCount=$newCount")

                val authenticationResponseJson = buildAssertionResponseJson(
                    credentialIdB64Url = credentialIdB64Url,
                    clientDataJson = clientDataJson,
                    authenticatorData = authenticatorData,
                    signature = signature,
                )

                runOnUiThread {
                    val credential = PublicKeyCredential(authenticationResponseJson)
                    val resultData = Intent()
                    PendingIntentHandler.setGetCredentialResponse(resultData, GetCredentialResponse(credential))
                    setResult(RESULT_OK, resultData)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "登入流程發生未預期例外：${e.message}", e)
                runOnUiThread { finishWithError("Unexpected error: ${e.message}") }
            }
        }.start()
    }

    private fun buildAssertionResponseJson(
        credentialIdB64Url: String,
        clientDataJson: ByteArray,
        authenticatorData: ByteArray,
        signature: ByteArray,
    ): String {
        val response = JSONObject()
            .put("clientDataJSON", b64UrlEncode(clientDataJson))
            .put("authenticatorData", b64UrlEncode(authenticatorData))
            .put("signature", b64UrlEncode(signature))

        return JSONObject()
            .put("id", credentialIdB64Url)
            .put("rawId", credentialIdB64Url)
            .put("type", "public-key")
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
                    // 允許重試，不在此結束流程。
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
        PendingIntentHandler.setGetCredentialException(resultData, GetCredentialUnknownException(message))
        setResult(RESULT_CANCELED, resultData)
        finish()
    }

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}
