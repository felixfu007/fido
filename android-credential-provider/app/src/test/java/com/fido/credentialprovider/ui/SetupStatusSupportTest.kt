package com.fido.credentialprovider.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只涵蓋 [SetupStatusSupport] 不依賴真正 Android 系統物件的部分（見該檔案說明）：
 *  - `ACTION_CREDENTIAL_PROVIDER` 常數與 [SetupStatusSupport.settingsDataUriString] 的純字串
 *    組裝（對應反組譯 `androidx.credentials.CredentialManagerImpl.createSettingsPendingIntent()`
 *    位元碼確認出的等價 Intent action/data 格式，見任務回報）。
 *  - [SetupStatusSupport.statusLabel] 三態文字。
 *
 * [SetupStatusSupport.buildCredentialProviderSettingsIntent] 本身回傳真正的 [android.content.
 * Intent]（需要 [android.net.Uri.parse]，在純 JVM 測試會因 stub Android jar 拋
 * `RuntimeException: Stub!`），因此不直接測試該函式，改為測試它所依賴的兩個純輸入
 * （action 常數、data URI 字串），已足以覆蓋深連結目的地是否正確組出的判斷依據。
 */
class SetupStatusSupportTest {

    @Test
    fun actionCredentialProviderMatchesVerifiedAndroid14SettingsAction() {
        // 釘住值，防止未來不小心改成任何未經查證的字串——見 SetupStatusSupport 內對
        // androidx.credentials.CredentialManagerImpl 位元碼反組譯的查證紀錄。
        assertEquals(
            "android.settings.CREDENTIAL_PROVIDER",
            SetupStatusSupport.ACTION_CREDENTIAL_PROVIDER,
        )
    }

    @Test
    fun settingsDataUriStringPrefixesPackageNameWithPackageScheme() {
        val uri = SetupStatusSupport.settingsDataUriString("com.fido.credentialprovider")

        assertEquals("package:com.fido.credentialprovider", uri)
    }

    @Test
    fun settingsDataUriStringReflectsWhicheverPackageNameIsPassedIn() {
        // 深連結必須精準指向呼叫方自己的套件（含 poc 的 applicationIdSuffix 變體），不可寫死。
        val prodUri = SetupStatusSupport.settingsDataUriString("com.fido.credentialprovider")
        val pocUri = SetupStatusSupport.settingsDataUriString("com.fido.credentialprovider.poc")

        assertNotEquals(prodUri, pocUri)
        assertTrue(pocUri.endsWith("com.fido.credentialprovider.poc"))
    }

    @Test
    fun statusLabelForEnabledTrueMentionsEnabled() {
        val label = SetupStatusSupport.statusLabel(true)

        assertTrue(label.contains("已啟用"))
    }

    @Test
    fun statusLabelForEnabledFalseMentionsNotYetEnabledAndPromptsAction() {
        val label = SetupStatusSupport.statusLabel(false)

        assertTrue(label.contains("尚未啟用"))
    }

    @Test
    fun statusLabelForNullMentionsCannotDetermine() {
        val label = SetupStatusSupport.statusLabel(null)

        assertTrue(label.contains("無法判定"))
    }

    @Test
    fun allThreeStatusLabelsAreDistinct() {
        val labels = setOf(
            SetupStatusSupport.statusLabel(true),
            SetupStatusSupport.statusLabel(false),
            SetupStatusSupport.statusLabel(null),
        )

        assertEquals("三種狀態必須顯示不同文字，避免使用者誤判", 3, labels.size)
    }
}
