package com.fido.credentialprovider.ui

import android.content.Intent
import android.net.Uri

/**
 * [SetupStatusActivity] 用到的框架無關邏輯，拆出來以利純 JVM 單元測試——比照
 * `com.fido.credentialprovider.webauthn.OriginResolver.apkKeyHashOrigin` 的既有慣例：需要真正
 * Android 系統物件（[android.content.Context]、[android.credentials.CredentialManager] 等）的膠水
 * 程式碼留在 Activity 裡（無法在純 JVM 測試建構，需模擬器/實機驗證），可測試的決策/字串組裝邏輯
 * 抽到這裡（見 `SetupStatusSupportTest`）。
 */
internal object SetupStatusSupport {

    /**
     * Android 14 系統設定「憑證提供者」畫面的 Intent action。
     *
     * 【驗證來源，非猜測】本專案已依賴 `androidx.credentials:credentials:1.5.0`，其
     * `androidx.credentials.CredentialManager` 介面公開 `createSettingsPendingIntent()`
     * （見該 AAR `classes.jar` 內 `androidx.credentials.CredentialManager` 的 `javap` 輸出）。
     * 對其實作類別 `CredentialManagerImpl` 以 `javap -c` 反組譯位元碼確認，內部就是：
     * ```
     * Intent("android.settings.CREDENTIAL_PROVIDER").setData(Uri.parse("package:" + context.packageName))
     * ```
     * 這裡沒有直接呼叫 androidx 的 `createSettingsPendingIntent()`——它回傳 `PendingIntent`
     * 且必須透過 `androidx.credentials.CredentialManager.create(context)` 執行個體取得，兩者都是
     * 真正的 Android 物件、無法在純 JVM 單元測試建構或斷言內容。改成自行組出**行為等價**的
     * `Intent`（同一個 action + 同一個 data URI 格式），交給 [SetupStatusActivity] 直接
     * `startActivity(...)`，同時讓組裝邏輯（action 常數、data URI 字串格式）能被
     * `SetupStatusSupportTest` 覆蓋。
     *
     * 另交叉核對 Android 14 framework `android.jar`（`android-34/android.jar`）：
     * `android.credentials.CredentialManager` 沒有對應的公開設定常數，此 action 字串屬於
     * `android.provider.Settings` 慣例但未在 API 34 的 `Settings` public stub 中以具名常數形式
     * 公開（仍是有效、androidx 官方程式碼實際使用的字串值，只是沒有對應的 `Settings.ACTION_*`
     * 具名常數可以引用）。
     */
    internal const val ACTION_CREDENTIAL_PROVIDER = "android.settings.CREDENTIAL_PROVIDER"

    /**
     * 對應上述 `Uri.parse("package:" + packageName)`：純字串組裝，不呼叫任何 Android API，
     * 可在純 JVM 單元測試驗證格式正確。
     */
    internal fun settingsDataUriString(packageName: String): String = "package:$packageName"

    /**
     * 組出可直接 `startActivity(...)` 的深連結 Intent，導向系統「憑證提供者」設定畫面並預先
     * 定位到本 App（`package:` data URI 讓系統設定直接展開本 App 的 provider 開關，而非只列出
     * 一般清單）。使用真正的 [Intent]/[Uri]，無法在純 JVM 單元測試建構，故未被
     * `SetupStatusSupportTest` 直接覆蓋——測試改為驗證上面兩個純字串/常數輸入是否正確
     * （見該測試檔案說明）。
     */
    fun buildCredentialProviderSettingsIntent(packageName: String): Intent =
        Intent(ACTION_CREDENTIAL_PROVIDER).setData(Uri.parse(settingsDataUriString(packageName)))

    /**
     * provider 啟用狀態的三態顯示文字：
     *  - `true`：系統回報已啟用
     *  - `false`：系統回報尚未啟用
     *  - `null`：查詢失敗/無法取得系統服務（見 [SetupStatusActivity] 呼叫端的防禦式處理）
     *
     * 純字串組裝、不含任何 Android 框架呼叫，可在純 JVM 單元測試逐字比對（比照本專案既有慣例：
     * 動態提示文字採直接組字串而非一律經 `strings.xml`，見 `CreatePasskeyActivity`/
     * `GetPasskeyActivity` 內的 `Toast`/`Log` 訊息寫法）。
     */
    fun statusLabel(enabled: Boolean?): String = when (enabled) {
        true -> "目前狀態：已啟用（此裝置可使用「$STATUS_APP_LABEL」進行 passkey 註冊/登入）"
        false -> "目前狀態：尚未啟用，請點選下方按鈕前往系統設定啟用「$STATUS_APP_LABEL」"
        null -> "目前狀態：無法判定，請點選下方按鈕前往系統設定確認"
    }

    /** [statusLabel] 內引用的固定顯示名稱，抽成常數避免三處文案各自硬寫、日後改名漏改。 */
    internal const val STATUS_APP_LABEL = "FIDO Authenticator"
}
