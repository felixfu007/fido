package com.fido.credentialprovider.webauthn

import android.content.pm.SigningInfo
import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest
import java.util.Base64

/**
 * 對應 `docs/origin-binding.md` 第 6 節：從呼叫方 [CallingAppInfo] 動態解析「經驗證的
 * origin」，取代任何寫死值（原 `PocConfig.ORIGIN`，已移除）。
 *
 * provider **永不寫入自己臆造的 origin**：
 *  - 瀏覽器路徑：`CallingAppInfo.getOrigin(allowlist)` 對受信任瀏覽器回傳的 web origin。
 *  - 原生 App 路徑：由呼叫方**實際簽章憑證**推導的 `android:apk-key-hash:...`（App 無法偽造
 *    他人簽章，OS 層擔保）。
 *  - 兩者皆非呼叫方「隨口宣稱」的字串。**最終授權仍由伺服器把關**
 *    （`tenants.expected_origin` ∪ `tenant_app_bindings`），provider 端不持有、也不應該
 *    持有任何租戶的 allowlist（見 origin-binding.md 6.2 要點）。
 */
object OriginResolver {

    /**
     * 受信任瀏覽器 allowlist（provider 內建靜態資產，非逐租戶資料，見 origin-binding.md
     * 第 6.4 節）。格式為 Credential Manager 定義的 privileged allowlist JSON。
     *
     * 【已知限制，非本文件拍板範圍】目前僅收錄 Chrome 穩定版正式簽章，對齊 PoC/模擬器最常見的
     * 測試瀏覽器；origin-binding.md 6.4 明訂「具體採用哪份 allowlist、如何維護更新，屬實作細節，
     * dev-engineer 於實作時確認」。正式上線前應擴充到 Android/Google 發布的完整清單（含
     * Chrome Beta/Canary、Samsung Internet、Firefox 等），並建立指紋輪替的更新流程；此為
     * 已知待辦，見任務回報「開放問題」。
     */
    private const val PRIVILEGED_BROWSER_ALLOWLIST_JSON = """
        {
          "apps": [
            {
              "type": "android",
              "info": {
                "package_name": "com.android.chrome",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "7C:11:C6:EE:34:96:71:1D:75:26:32:AC:55:D9:6A:C4:23:D9:0F:7A:7F:47:D0:9E:26:C1:66:33:E2:B0:E7:52"
                  }
                ]
              }
            }
          ]
        }
    """

    /** Origin 來源型別，未來若需要可回傳給呼叫端用於本機記錄；伺服器端對應 `OriginType`（`audit_log.detail.originType`）。 */
    enum class SourceType { WEB, NATIVE_APP }

    sealed class OriginDecision {
        data class UseOrigin(val origin: String, val sourceType: SourceType) : OriginDecision()
        data class Reject(val reason: String) : OriginDecision()
    }

    /**
     * 對應 origin-binding.md 6.2 演算法。任何冒充/無法取得可信呼叫方身分的情況，回傳
     * [OriginDecision.Reject]；呼叫端（[com.fido.credentialprovider.ui.CreatePasskeyActivity] /
     * [com.fido.credentialprovider.ui.GetPasskeyActivity]）收到 Reject 時必須直接結束流程、
     * 不得產生任何簽章（見 origin-binding.md 6.3 拒絕條件）。
     */
    fun resolveTrustedOrigin(callingAppInfo: CallingAppInfo?): OriginDecision {
        if (callingAppInfo == null) {
            // 對應 6.3 條件 2：無法取得可信呼叫方身分。
            return OriginDecision.Reject("NO_CALLING_APP_INFO")
        }

        // (1) 瀏覽器路徑：getOrigin() 對 allowlist 內的受信任瀏覽器回傳其代表的 web origin；
        //     呼叫方宣稱是 privileged caller（package 在 allowlist 內）但簽章不符時拋例外
        //     ——典型的重打包冒充，對應 6.3 條件 1，必須直接拒絕，不落到 (2) 的原生 App 路徑。
        val webOrigin: String? = try {
            callingAppInfo.getOrigin(PRIVILEGED_BROWSER_ALLOWLIST_JSON)
        } catch (e: IllegalArgumentException) {
            return OriginDecision.Reject("UNTRUSTED_PRIVILEGED_CALLER: ${e.message}")
        } catch (e: IllegalStateException) {
            return OriginDecision.Reject("UNTRUSTED_PRIVILEGED_CALLER: ${e.message}")
        }

        if (webOrigin != null) {
            return OriginDecision.UseOrigin(webOrigin, SourceType.WEB)
        }

        // (2) 原生 App 路徑：呼叫方是一般 App 自身（webOrigin == null，非受信任瀏覽器代表某網頁）。
        //     以呼叫方「實際簽章憑證」計算 apk-key-hash origin。
        val appOrigin = try {
            buildAppOrigin(callingAppInfo.signingInfo)
        } catch (e: Exception) {
            return OriginDecision.Reject("CANNOT_DERIVE_APP_ORIGIN: ${e.message}")
        }
        return OriginDecision.UseOrigin(appOrigin, SourceType.NATIVE_APP)
    }

    /**
     * 取「目前簽章者」憑證並換算成 apk-key-hash origin：[SigningInfo.getApkContentsSigners]
     * 回傳目前實際簽署此 APK 的憑證（不含僅存在於金鑰輪替歷史、已不生效的舊簽章）。
     *
     * 【實作判斷，docs 未明確規定，記於此供後續複核】若 [SigningInfo.hasMultipleSigners] 為
     * true（多簽章方案，需要多把金鑰共同簽署才算合法，非金鑰輪替），沒有單一「目前簽章」可
     * 對應成單一 apk-key-hash 字串，直接視為無法解析（拒絕），不臆測要取哪一張憑證——
     * 這與 6.2 節「provider 永不寫入自己臆造的 origin」的精神一致。
     *
     * 這個函式本身需要真正的 [SigningInfo]（Android 系統物件），不適合純 JVM 單元測試；實際
     * 「憑證 DER bytes -> apk-key-hash 字串」的可測試邏輯抽到 [apkKeyHashOrigin]（見該函式
     * 說明與 `OriginResolverTest`）。
     */
    private fun buildAppOrigin(signingInfo: SigningInfo?): String {
        if (signingInfo == null) {
            throw IllegalStateException("signingInfo unavailable")
        }
        if (signingInfo.hasMultipleSigners()) {
            throw IllegalStateException("multiple current signers not supported for apk-key-hash origin")
        }
        val currentSignerCert = signingInfo.apkContentsSigners?.firstOrNull()
            ?: throw IllegalStateException("no current signer certificate")
        return apkKeyHashOrigin(currentSignerCert.toByteArray())
    }

    /**
     * `android:apk-key-hash:<BASE64URL_NOPAD(SHA-256(certificateDer))>`（origin-binding.md
     * 第 3 節）。純位元組轉換，不碰任何 Android 框架 API，故可在純 JVM 單元測試驗證
     * （見 `OriginResolverTest`），對齊 `com.fido.credentialprovider.webauthn` 套件其餘類別
     * （[Cbor]、[AttestationObjectBuilder] 等）刻意保持框架無關以利測試的既有慣例。
     */
    internal fun apkKeyHashOrigin(certificateDer: ByteArray): String {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificateDer)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)
        return "android:apk-key-hash:$encoded"
    }
}
