package com.fido.credentialprovider.crossdevice

/**
 * 解析桌機瀏覽器 QR 掃碼跨裝置登入（情境三）的 deep link（App Link）。
 * 對應設計文件 `docs/decisions/qr-cross-device-login-design.md` 3.1 與 4.2 步驟 1、
 * `docs/api-contract.md` §3.4.A 回傳的 `qrUrl` 格式：
 *
 * ```
 * https://<fido-app-link-host>/x/<xdevId>
 * ```
 *
 * **設計決定（依設計文件 3.1 第 3 點）**：App **只解析 `xdevId`，忽略 URL 內的 host**——QR 內容
 * 本身可能被竄改成任意 host，意圖把 App 導向偽造的 fido-server；App 內建的
 * [CrossDeviceConfig.FIDO_SERVER_BASE_URL] 才是唯一權威的 fido-server 位置，host 值本身
 * 不做任何比對、也不用於任何後續信任判斷。因此這裡的驗證只檢查「這是不是一個合法的
 * `https://.../x/<xdevId>` 形狀」。
 *
 * 純字串/正規表示式邏輯，不依賴任何 Android 框架類別（`android.net.Uri` 在純 JVM 單元測試環境
 * 會丟出 stub 例外，見 `com.fido.credentialprovider.ui.SetupStatusSupport` 檔頭說明的既有慣例），
 * 故可在 JVM 單元測試完整覆蓋（見 `CrossDeviceDeepLinkParserTest`）。呼叫端
 * （[com.fido.credentialprovider.ui.CrossDeviceLoginActivity]）把
 * `Intent.getDataString()`（`String?`）傳進來即可，格式不符時顯示錯誤並結束、不崩潰
 * （設計文件 4.2 步驟 1）。
 */
object CrossDeviceDeepLinkParser {

    sealed class Result {
        data class Valid(val xdevId: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    // xdevId 由伺服器以 ≥256-bit 亂數 base64url 產生（api-contract.md §3.4.A「不透明高熵
    // base64url」），故只接受 base64url 字元集（A-Z a-z 0-9 - _），且不得為空字串。
    // scheme 必須是 https（本專案不支援明文 deep link 承載 capability 值）；host 允許任意內容
    // （本來就不採信，見上方檔頭說明），但仍要求語法上存在非空 host 區段，避免
    // "https:///x/xxx" 這種殘缺 URL 被誤判為合法。
    private val XDEV_LINK_PATTERN = Regex("^https://[^/?#]+/x/([A-Za-z0-9_-]+)/?$")

    fun parse(uriString: String?): Result {
        if (uriString.isNullOrBlank()) {
            return Result.Invalid("EMPTY_URI")
        }
        val match = XDEV_LINK_PATTERN.matchEntire(uriString.trim())
            ?: return Result.Invalid("MALFORMED_XDEV_LINK")
        val xdevId = match.groupValues[1]
        return Result.Valid(xdevId)
    }
}
