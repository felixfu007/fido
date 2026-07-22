package com.fido.credentialprovider.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * 【清單項目 2、4 核心】Android Keystore 硬體金鑰產生：
 *  - `setIsStrongBoxBacked(true)` 優先；捕捉 [StrongBoxUnavailableException] 後 fallback 到 TEE
 *    （不設 StrongBox flag，讓 Keymaster/KeyMint 自行決定落在 TEE）。
 *  - `setAttestationChallenge()` 綁定本次 WebAuthn ceremony 的 challenge bytes（原封不動傳入，
 *    不做任何轉碼，見 [generate] 的 `attestationChallenge` 參數）。
 *  - 產生後以 [KeyInfo.getSecurityLevel]（API 31+）主動查核實際安全等級；非硬體等級
 *    （SOFTWARE / UNKNOWN）一律視為失敗並刪除剛產生的金鑰，**絕不**把軟體金鑰包裝成功回傳
 *    （對齊 CLAUDE.md「強制要求 TEE/StrongBox，不通過則拒絕註冊」）。
 */
object HardwareKeyManager {

    private const val TAG = "HardwareKeyManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CURVE_P256 = "secp256r1"

    /** 供 qa-engineer / 回報 log 檢索用的固定字串常數，對齊清單項目 2 通過判準第 1 點。 */
    const val LOG_STRONGBOX_UNAVAILABLE_FALLBACK_TEE = "STRONGBOX_UNAVAILABLE_FALLBACK_TEE"

    /**
     * 【PoC 診斷專用旗標，預設 false，正式邏輯完全不受影響】模擬器沒有真正硬體安全區，
     * [detectSecurityLevel] 必然回報 `SOFTWARE`，[generate] 依清單項目 2 的把關邏輯會刪除該
     * 金鑰並拒絕使用——這是**正確**行為，但也代表模擬器上永遠無法產生「被允許送出」的
     * attestationObject，導致清單項目 3（android-key CBOR 結構本身在真實 Android Keystore
     * 產出下能否被伺服器正確解析）無法用真實裝置憑證鏈驗證，只能停留在 JVM 單元測試組出的
     * 自簽測試憑證鏈（見 `AttestationObjectBuilderTest`）。
     *
     * 開啟此旗標時，[generate] 對軟體等級金鑰**不刪除、不拒絕**，改為以
     * [KeyGenOutcome.Success.bypassedHardwareGateForDiagnostics]`=true` 標記後原樣回傳，讓
     * PoC harness 能取得模擬器 Keystore 真實吐出的 android-key attestation 憑證鏈，組出真正
     * 由 Android Keystore（而非 JVM 自簽測試 fixture）簽發的 attestationObject，送到
     * fido-server 驗證 CBOR 結構是否可解析。**伺服器端的硬體閘門與憑證鏈信任驗證完全不受此
     * 旗標影響**——`fido.attestation.mode=real` 下伺服器仍會依實際憑證鏈判斷結果（模擬器產生
     * 的憑證鏈預期會被伺服器擋下，見 docs/android-poc-checklist.md 第 2 節）。
     *
     * 此旗標只能透過 [com.fido.credentialprovider.harness.HarnessActivity] 的診斷用開關手動
     * 開啟，正式 CreatePasskeyActivity／CredentialProviderService 的一般路徑不會、也不應該
     * 主動開啟它。
     */
    @Volatile
    var diagnosticsAllowSoftwareKeyForPoCInspection: Boolean = false

    enum class DetectedLevel { STRONG_BOX, TEE, SOFTWARE, UNKNOWN }

    sealed class KeyGenOutcome {
        data class Success(
            val alias: String,
            val publicKey: ECPublicKey,
            val privateKey: PrivateKey,
            val certificateChain: List<X509Certificate>,
            val detectedLevel: DetectedLevel,
            val requestedStrongBox: Boolean,
            /** true 代表這把金鑰其實未達硬體安全等級，只是因 [diagnosticsAllowSoftwareKeyForPoCInspection] 診斷旗標而放行，見上方說明。 */
            val bypassedHardwareGateForDiagnostics: Boolean = false,
        ) : KeyGenOutcome()

        /**
         * 硬體安全等級不足（軟體金鑰）或產生過程失敗。[alreadyDeletedFromKeystore] 恆為
         * true——凡是產生出軟體等級金鑰的情況，本物件在建構前就已把該別名從 Keystore 刪除，
         * 呼叫端不需要、也不應該再嘗試用這把「被拒絕」的金鑰做任何事。
         */
        data class Rejected(
            val reason: String,
            val detectedLevel: DetectedLevel?,
            val alreadyDeletedFromKeystore: Boolean = true,
        ) : KeyGenOutcome()
    }

    /**
     * @param alias                產生金鑰用的 Keystore 別名（呼叫端負責確保唯一性）
     * @param attestationChallenge 本次 WebAuthn ceremony 的 challenge 原始 bytes（base64url
     *                             解碼後），會原封不動傳入 `setAttestationChallenge()`
     */
    fun generate(alias: String, attestationChallenge: ByteArray): KeyGenOutcome {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // 保守起見：若此別名已存在（理論上不該發生，因為呼叫端以 credentialId 派生別名），先清掉，
        // 避免拿到舊金鑰造成 attestationChallenge 綁定錯誤的 ceremony。
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }

        var requestedStrongBox = true
        val generatedWithStrongBox: Boolean = try {
            generateKeyPair(alias, attestationChallenge, strongBoxBacked = true)
            true
        } catch (e: StrongBoxUnavailableException) {
            Log.w(TAG, "$LOG_STRONGBOX_UNAVAILABLE_FALLBACK_TEE: StrongBox 不可用（${e.message}），改用 TEE fallback。")
            requestedStrongBox = false
            try {
                generateKeyPair(alias, attestationChallenge, strongBoxBacked = false)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "TEE fallback 金鑰產生仍失敗：${fallbackError.message}", fallbackError)
                return KeyGenOutcome.Rejected(
                    reason = "TEE fallback 金鑰產生失敗：${fallbackError.message}",
                    detectedLevel = null,
                    alreadyDeletedFromKeystore = true,
                )
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "StrongBox 金鑰產生失敗（非 StrongBoxUnavailableException）：${e.message}", e)
            return KeyGenOutcome.Rejected(
                reason = "金鑰產生失敗：${e.message}",
                detectedLevel = null,
                alreadyDeletedFromKeystore = true,
            )
        }

        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val certChain: Array<Certificate> = keyStore.getCertificateChain(alias)
            ?: run {
                keyStore.deleteEntry(alias)
                return KeyGenOutcome.Rejected("Keystore 未回傳憑證鏈", null)
            }
        val x509Chain = certChain.map { it as X509Certificate }

        val detectedLevel = detectSecurityLevel(privateKey)
        Log.i(
            TAG,
            "金鑰產生完成：alias=$alias requestedStrongBox=$requestedStrongBox " +
                "generatedWithStrongBoxFlag=$generatedWithStrongBox detectedLevel=$detectedLevel",
        )

        val hardwareLevelMet = detectedLevel == DetectedLevel.STRONG_BOX || detectedLevel == DetectedLevel.TEE
        if (!hardwareLevelMet && !diagnosticsAllowSoftwareKeyForPoCInspection) {
            // 清單項目 2 通過判準第 2 點：偵測到非硬體等級 -> 主動放棄，不送出註冊。
            Log.w(TAG, "偵測到非硬體安全等級（$detectedLevel），依 CLAUDE.md 強制硬體安全區求，拒絕使用此金鑰。")
            keyStore.deleteEntry(alias)
            return KeyGenOutcome.Rejected(
                reason = "此裝置不支援硬體安全金鑰（偵測到等級：$detectedLevel）",
                detectedLevel = detectedLevel,
                alreadyDeletedFromKeystore = true,
            )
        }
        if (!hardwareLevelMet) {
            Log.w(
                TAG,
                "【PoC 診斷旗標開啟】偵測到非硬體安全等級（$detectedLevel），" +
                    "本應拒絕，但 diagnosticsAllowSoftwareKeyForPoCInspection=true，" +
                    "放行僅供檢視真實 Keystore attestation 憑證鏈結構，此金鑰不代表通過硬體閘門。",
            )
        }

        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(x509Chain[0].publicKey.encoded),
        ) as ECPublicKey

        return KeyGenOutcome.Success(
            alias = alias,
            publicKey = publicKey,
            privateKey = privateKey,
            certificateChain = x509Chain,
            detectedLevel = detectedLevel,
            requestedStrongBox = requestedStrongBox,
            bypassedHardwareGateForDiagnostics = !hardwareLevelMet,
        )
    }

    private fun generateKeyPair(alias: String, attestationChallenge: ByteArray, strongBoxBacked: Boolean) {
        val purposes = KeyProperties.PURPOSE_SIGN
        val specBuilder = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE_P256))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(attestationChallenge)

        if (strongBoxBacked) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(specBuilder.build())
        generator.generateKeyPair()
    }

    /**
     * 以 [KeyInfo.getSecurityLevel]（API 31+，本專案 minSdk=34 恆可用）主動查核金鑰實際安全
     * 等級，而非只信任「呼叫 setIsStrongBoxBacked 沒有拋例外」——這是清單項目 2「絕不靜默落
     * 純軟體」的關鍵把關步驟。
     */
    private fun detectSecurityLevel(privateKey: PrivateKey): DetectedLevel {
        return try {
            val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> DetectedLevel.STRONG_BOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> DetectedLevel.TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> DetectedLevel.SOFTWARE
                else -> DetectedLevel.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "KeyInfo.getSecurityLevel() 查核失敗：${e.message}", e)
            DetectedLevel.UNKNOWN
        }
    }
}
