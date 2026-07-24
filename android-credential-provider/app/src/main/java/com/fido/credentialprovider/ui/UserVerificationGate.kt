package com.fido.credentialprovider.ui

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * WebAuthn UV（User Verification）閘門，從 [GetPasskeyActivity]（原 `requireUserVerification`
 * 私有方法）抽出，供 [CrossDeviceLoginActivity]（情境三跨裝置 QR 登入，新入口）共用。對應設計文件
 * `docs/decisions/qr-cross-device-login-design.md` 4.3「直接重用
 * `GetPasskeyActivity.requireUserVerification`」。
 *
 * **重構原則（務必遵守）**：純粹搬移既有邏輯，**不改變任何行為**——判斷條件
 * （`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`）、PoC 環境「無可用生物辨識時直接放行 UV」的降級邏輯、
 * log 訊息皆與搬移前逐字相同。[GetPasskeyActivity] 改為委派呼叫本物件（其私有
 * `requireUserVerification` 方法簽章、呼叫端 `onCreate` 呼叫方式不變）。
 * [com.fido.credentialprovider.ui.CreatePasskeyActivity] 目前仍保留自己那份完全相同的既有實作
 * （獨立、未受本次重構影響，本次任務範圍邊界「不影響同裝置流程」未要求也不應變更該檔案）。
 */
object UserVerificationGate {

    private const val TAG = "UserVerificationGate"

    fun require(
        activity: AppCompatActivity,
        title: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val biometricManager = BiometricManager.from(activity)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = biometricManager.canAuthenticate(allowed)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "此裝置無可用的生物辨識/裝置解鎖（code=$canAuthenticate），PoC 環境下改為直接放行 UV。")
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
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
}
