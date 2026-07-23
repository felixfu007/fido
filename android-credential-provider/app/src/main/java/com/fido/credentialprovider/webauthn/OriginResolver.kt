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
     * 第 6.4 節）。格式為 Credential Manager 定義的 privileged allowlist JSON
     * （`androidx.credentials.provider.CallingAppInfo#getOrigin(String)` 官方文件記載的
     * `{"apps":[{"type":"android","info":{"package_name":...,"signatures":[{"build":...,
     * "cert_fingerprint_sha256":...}]}}]}` schema）。
     *
     * 【指紋來源，供未來複查】以下所有 `cert_fingerprint_sha256` 皆逐一核對下列權威來源，
     * 不採信任何未經查證的數值：
     *  - **主要來源**：Google 官方、目前仍在線上服務的 Google Password Manager / Credential
     *    Manager 特權呼叫方清單 `https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json`
     *    （2026-07-23 直接 fetch 驗證存在且格式相符；此 URL 由開源密碼管理器
     *    `bitwarden/android` 的 `.github/workflows/cron-sync-google-priviledged-browsers.yml`
     *    自動化排程同步宣告為官方來源，非本專案自行猜測）。
     *  - **交叉驗證**：`github.com/bitwarden/android`
     *    （`app/src/main/assets/fido2_privileged_google.json`）與
     *    `github.com/Kunzisoft/KeePassDX`
     *    （`app/src/free/assets/passkeys_privileged_apps_google.json`）兩個各自獨立、皆為
     *    生產環境使用中的開源密碼管理器專案，其自動同步腳本抓到的內容與上述主要來源逐位元組
     *    相符。
     *
     * 【重要更正】舊版此常數的 Chrome 穩定版指紋 `7C:11:C6:EE:34:96:71:1D:75:26:32:AC:55:D9:
     * 6A:C4:23:D9:0F:7A:7F:47:D0:9E:26:C1:66:33:E2:B0:E7:52` 經查**在任何權威或第三方公開來源
     * 均查無比對**（Sourcegraph 全網搜尋比對 0 筆），研判為先前未經查證即寫入的錯誤數值——
     * 依任務規格「錯的條目比沒有條目更糟」，已於本次更新替換為下方查證過的正確值
     * `F0:FD:6C:5B:...`（與官方來源、兩個獨立第三方專案三方一致）。
     *
     * 【涵蓋範圍，仍非窮舉】目前收錄 Chrome（穩定版/Beta/Canary）、Firefox 穩定版、Samsung
     * Internet 穩定版、Microsoft Edge 穩定版、Brave 穩定版，皆對應主要來源清單中 `type: android`
     * 且未標記為社群自行回報（non-Google-blessed）的正式項目。主要來源實際收錄更多瀏覽器/版本
     * （如 Chrome Dev、Firefox Beta、Samsung Internet Beta、Vivaldi、Yandex、DuckDuckGo、
     * Opera、Naver Whale 等），基於範圍控制未逐一納入——這些**並非查證失敗**，只是尚未收錄；
     * 之後若需擴充，可直接從主要來源的既有清單複製對應項目，不需重新查證。仍屬已知待辦：
     * 尚未建立指紋輪替的自動化更新流程（可參考 bitwarden/android 的排程同步做法）。
     */
    internal const val PRIVILEGED_BROWSER_ALLOWLIST_JSON = """
        {
          "apps": [
            {
              "type": "android",
              "info": {
                "package_name": "com.android.chrome",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.chrome.beta",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "DA:63:3D:34:B6:9E:63:AE:21:03:B4:9D:53:CE:05:2F:C5:F7:F3:C5:3A:AB:94:FD:C2:A2:08:BD:FD:14:24:9C"
                  },
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "3D:7A:12:23:01:9A:A3:9D:9E:A0:E3:43:6A:B7:C0:89:6B:FB:4F:B6:79:F4:DE:5F:E7:C2:3F:32:6C:8F:99:4A"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.chrome.canary",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "20:19:DF:A1:FB:23:EF:BF:70:C5:BC:D1:44:3C:5B:EA:B0:4F:3F:2F:F4:36:6E:9A:C1:E3:45:76:39:A2:4C:FC"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "org.mozilla.firefox",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.sec.android.app.sbrowser",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"
                  },
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.microsoft.emmx",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.brave.browser",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
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
