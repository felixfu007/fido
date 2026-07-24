package com.fido.credentialprovider.crossdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrossDeviceDeepLinkParser] 涵蓋設計文件 4.2 步驟 1「解析 deep link…格式不符 → 顯示錯誤並
 * 結束、不崩潰」的合法/不合法格式判斷。純字串邏輯，不依賴 `android.net.Uri`（見該類別檔頭說明），
 * 可在 JVM 單元測試環境完整覆蓋。
 */
class CrossDeviceDeepLinkParserTest {

    @Test
    fun `valid https link with xdevId is parsed`() {
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/x/AbC123-_xyz")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Valid)
        assertEquals("AbC123-_xyz", (result as CrossDeviceDeepLinkParser.Result.Valid).xdevId)
    }

    @Test
    fun `host content is irrelevant to validity — any host shape is accepted`() {
        // 設計文件 3.1 第 3 點：App 不採信、不比對 host 內容本身，只要求語法上存在 host 區段。
        // 這裡刻意用一個「看起來像惡意網域」的 host，證明解析仍然成功（因為 App 後續完全不使用
        // 這個 host 值做任何信任判斷，只取 xdevId，見 CrossDeviceConfig 檔頭說明）。
        val result = CrossDeviceDeepLinkParser.parse("https://evil.attacker.example/x/AbC123")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Valid)
        assertEquals("AbC123", (result as CrossDeviceDeepLinkParser.Result.Valid).xdevId)
    }

    @Test
    fun `trailing slash after xdevId is tolerated`() {
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/x/AbC123/")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Valid)
        assertEquals("AbC123", (result as CrossDeviceDeepLinkParser.Result.Valid).xdevId)
    }

    @Test
    fun `null uri is invalid`() {
        val result = CrossDeviceDeepLinkParser.parse(null)

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
        assertEquals("EMPTY_URI", (result as CrossDeviceDeepLinkParser.Result.Invalid).reason)
    }

    @Test
    fun `blank uri is invalid`() {
        val result = CrossDeviceDeepLinkParser.parse("   ")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
        assertEquals("EMPTY_URI", (result as CrossDeviceDeepLinkParser.Result.Invalid).reason)
    }

    @Test
    fun `http scheme (non-https) is rejected`() {
        val result = CrossDeviceDeepLinkParser.parse("http://fido-app-link.example.com/x/AbC123")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
        assertEquals("MALFORMED_XDEV_LINK", (result as CrossDeviceDeepLinkParser.Result.Invalid).reason)
    }

    @Test
    fun `missing x path segment is rejected`() {
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/AbC123")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }

    @Test
    fun `empty xdevId segment is rejected`() {
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/x/")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }

    @Test
    fun `xdevId with disallowed characters is rejected`() {
        // base64url 字元集不含 '+'、'/'、'='；伺服器產生的 xdevId 不應出現這些字元
        // （api-contract.md §3.4.A「不透明高熵 base64url」）。
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/x/abc+def/")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }

    @Test
    fun `missing host segment (malformed url) is rejected`() {
        val result = CrossDeviceDeepLinkParser.parse("https:///x/AbC123")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }

    @Test
    fun `extra path segments after xdevId are rejected`() {
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/x/AbC123/extra")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }

    @Test
    fun `query string appended to link is rejected`() {
        // 設計上 QR 只承載不透明 xdevId，不應有查詢字串；穩健起見拒絕而非默默忽略額外參數。
        val result = CrossDeviceDeepLinkParser.parse("https://fido-app-link.example.com/x/AbC123?foo=bar")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }

    @Test
    fun `completely unrelated arbitrary string is rejected`() {
        val result = CrossDeviceDeepLinkParser.parse("not a url at all")

        assertTrue(result is CrossDeviceDeepLinkParser.Result.Invalid)
    }
}
