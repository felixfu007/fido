package com.fido.credentialprovider.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.credentials.CredentialManager as FrameworkCredentialManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fido.credentialprovider.FidoCredentialProviderService
import com.fido.credentialprovider.R

/**
 * 【CLAUDE.md「啟動器畫面決策」Option B】`prod` flavor 唯一帶 `MAIN`/`LAUNCHER` 的元件。
 *
 * 背景：Android 14 新裝的 [FidoCredentialProviderService] 不會自動在系統 Credential Manager
 * 啟用，使用者須自行到系統設定手動開啟；若 `prod` 完全零啟動器（把 PoC harness 隔離出去後的
 * 副作用），App 安裝後不在 app drawer 出現、無處可點、無法深連結到設定、無法顯示「是否已啟用」。
 *
 * **硬性範圍邊界（人類已拍板，不得逾越）**：本畫面永遠只做三件事——
 *  1. 顯示 provider 是否已在系統啟用（[queryProviderEnabled]）
 *  2. 一顆深連結進系統「憑證提供者」設定的按鈕（[SetupStatusSupport.buildCredentialProviderSettingsIntent]）
 *  3. 版本 / 客服 / 隱私（個資法）文字
 *
 * **絕對不可**在此加入任何登入/註冊 ceremony（那永遠只能由系統以 PendingIntent 隱式啟動
 * [CreatePasskeyActivity]/[GetPasskeyActivity]）或裝置列表/撤銷 UI（那已由系統 Settings 的
 * provider 詳情頁 + 購物網站自家 `DeviceProxyController` 雙重滿足，見 CLAUDE.md 該決策全文）。
 * 一旦此畫面長出上述任一種 UI，即違反「非獨立 APP 跳轉」架構決策。
 */
class SetupStatusActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupStatusActivity"
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_status)

        statusText = findViewById(R.id.providerStatusText)
        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)
        val versionText = findViewById<TextView>(R.id.versionText)

        versionText.text = getString(R.string.setup_status_version_format, readVersionName())

        openSettingsButton.setOnClickListener {
            try {
                startActivity(SetupStatusSupport.buildCredentialProviderSettingsIntent(packageName))
            } catch (e: Exception) {
                // 理論上 Android 14+ 裝置皆有此系統設定畫面；防禦式處理避免非預期裝置差異
                // （如客製化 ROM 移除該畫面）讓整個 App 直接崩潰。
                Log.w(TAG, "無法開啟系統憑證提供者設定畫面：${e.message}", e)
                Toast.makeText(
                    this,
                    "無法開啟系統設定，請手動至「設定 > 密碼與帳戶 > 憑證提供者」開啟",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 使用者常見動線：點按鈕離開到系統設定切換開關，再切回本畫面——回來時重新查一次，
        // 讓顯示的狀態不會停留在切換前的舊值。
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = SetupStatusSupport.statusLabel(queryProviderEnabled())
    }

    /**
     * 對應 Android 14 (API 34) framework `android.credentials.CredentialManager
     * #isEnabledCredentialProviderService(ComponentName)`。
     *
     * 【API 查證，非猜測】以 `javap -public` 分別檢視：
     *  - `android-34/android.jar` 的 `android.credentials.CredentialManager`：**存在**
     *    `public boolean isEnabledCredentialProviderService(android.content.ComponentName)`。
     *  - 本專案依賴的 `androidx.credentials:credentials:1.5.0`（`androidx.credentials.
     *    CredentialManager`）：**不提供**對應方法（該介面只有 get/create/prepare/clear 相關
     *    credential 操作與 `createSettingsPendingIntent()`，沒有查詢啟用狀態的 API）。
     * 因此這裡改用 framework 版 `android.credentials.CredentialManager`（透過
     * `Context.CREDENTIAL_SERVICE` 系統服務取得，minSdk=34 恆可用），而非 androidx 版本。
     *
     * 回傳三態：`true`/`false` 為系統明確回報；`null` 代表查詢失敗（理論上不會發生，防禦式處理）。
     */
    private fun queryProviderEnabled(): Boolean? {
        return try {
            val manager = getSystemService(FrameworkCredentialManager::class.java) ?: return null
            val component = ComponentName(this, FidoCredentialProviderService::class.java)
            manager.isEnabledCredentialProviderService(component)
        } catch (e: Exception) {
            Log.w(TAG, "查詢 provider 啟用狀態失敗：${e.message}", e)
            null
        }
    }

    private fun readVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "查詢版本號失敗：${e.message}", e)
            "unknown"
        }
    }
}
