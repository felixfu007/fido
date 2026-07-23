package com.fido.credentialprovider.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * 對應 `docs/origin-binding.md` 第 6.2 節：原生 App 路徑的 origin 由呼叫方**實際簽章憑證**推導
 * `android:apk-key-hash:<BASE64URL_NOPAD(SHA-256(憑證 DER))>`。
 *
 * [OriginResolver.resolveTrustedOrigin] 本身需要真正的
 * [androidx.credentials.provider.CallingAppInfo] / [android.content.pm.SigningInfo]（Android
 * 系統物件，無法在純 JVM 單元測試建構，需模擬器/實機驗證，見任務回報），因此本測試只涵蓋不需要
 * 模擬器、框架無關的部分：(1) [OriginResolver.apkKeyHashOrigin] 純位元組轉換邏輯，比照
 * `AttestationObjectBuilderTest` 的模式；(2) [OriginResolver.PRIVILEGED_BROWSER_ALLOWLIST_JSON]
 * 的資料層驗證（合法 JSON、符合 schema、無重複、涵蓋任務要求的瀏覽器種類）——**不**驗證指紋本身
 * 的密碼學真實性，那需要人工比對常數上方註解列出的權威來源。
 */
class OriginResolverTest {

    @Test
    fun apkKeyHashOriginHasExpectedPrefixAndBase64UrlNoPadEncoding() {
        val certDer = "fake-certificate-der-bytes-for-test".toByteArray(Charsets.UTF_8)

        val origin = OriginResolver.apkKeyHashOrigin(certDer)

        assertTrue(origin.startsWith("android:apk-key-hash:"))
        val encodedPart = origin.removePrefix("android:apk-key-hash:")
        // base64url、無 padding：不應含 '+'、'/'、'='。
        assertTrue(encodedPart.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun apkKeyHashOriginMatchesIndependentlyComputedSha256Base64Url() {
        val certDer = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val origin = OriginResolver.apkKeyHashOrigin(certDer)

        val expectedFingerprint = MessageDigest.getInstance("SHA-256").digest(certDer)
        val expectedEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedFingerprint)
        assertEquals("android:apk-key-hash:$expectedEncoded", origin)
    }

    @Test
    fun differentCertificateBytesProduceDifferentOrigins() {
        val originA = OriginResolver.apkKeyHashOrigin(byteArrayOf(1, 2, 3))
        val originB = OriginResolver.apkKeyHashOrigin(byteArrayOf(4, 5, 6))

        assertNotEquals(originA, originB)
    }

    @Test
    fun sameCertificateBytesProduceSameOriginDeterministically() {
        val certDer = "deterministic-input".toByteArray(Charsets.UTF_8)

        val originFirst = OriginResolver.apkKeyHashOrigin(certDer)
        val originSecond = OriginResolver.apkKeyHashOrigin(certDer)

        assertEquals(originFirst, originSecond)
    }

    // ---- PRIVILEGED_BROWSER_ALLOWLIST_JSON：純資料層驗證 ----
    //
    // 這裡不能、也不嘗試驗證「哪些指紋在密碼學上是真的」——那必須靠人工比對權威來源
    // （見常數上方的來源註解）並定期複查，非 JVM 單元測試能自動化的範圍。這組測試只驗證
    // 「資料本身結構正確、無低級錯誤」：合法 JSON、符合 Android 文件記載的 schema、
    // 每個指紋是格式正確的 32-byte SHA-256（64 hex chars、以冒號分隔大寫成對）、
    // package_name 不重複、且涵蓋任務要求點名的瀏覽器種類（非僅 Chrome 穩定版）。

    private val allowlistTree by lazy {
        ObjectMapper().readTree(OriginResolver.PRIVILEGED_BROWSER_ALLOWLIST_JSON)
    }

    private fun allowlistPackageNames(): List<String> =
        allowlistTree["apps"].map { it["info"]["package_name"].asText() }

    @Test
    fun privilegedBrowserAllowlistIsValidJsonMatchingDocumentedSchema() {
        val apps = allowlistTree["apps"]
        assertTrue("allowlist 必須至少有一個 app 項目", apps.size() > 0)

        for (app in apps) {
            assertEquals("android", app["type"].asText())
            val info = app["info"]
            assertTrue(
                "package_name 不可為空",
                info["package_name"].asText().isNotBlank(),
            )
            val signatures = info["signatures"]
            assertTrue(
                "${info["package_name"].asText()} 至少需要一組 signature",
                signatures.size() > 0,
            )
            for (signature in signatures) {
                assertTrue(signature.has("build"))
                val fingerprint = signature["cert_fingerprint_sha256"].asText()
                // SHA-256 = 32 bytes，以冒號分隔的大寫 hex 表示為 32 組兩碼、共 95 字元。
                assertTrue(
                    "$fingerprint 不符合 SHA-256 冒號分隔 hex 格式",
                    fingerprint.matches(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")),
                )
            }
        }
    }

    @Test
    fun privilegedBrowserAllowlistHasNoDuplicatePackageNames() {
        val names = allowlistPackageNames()
        assertEquals(
            "package_name 不應重複（重複代表資料合併時出錯）",
            names.distinct().size,
            names.size,
        )
    }

    @Test
    fun privilegedBrowserAllowlistCoversMoreThanChromeStableOnly() {
        // 對應任務要求：不能只收錄 Chrome 穩定版，需涵蓋 Chrome Beta/Canary、Samsung
        // Internet、Firefox 等真實瀏覽器（見任務描述與常數上方「涵蓋範圍」註解）。
        val names = allowlistPackageNames()
        assertTrue("缺少 Chrome 穩定版", names.contains("com.android.chrome"))
        assertTrue("缺少 Chrome Beta", names.contains("com.chrome.beta"))
        assertTrue("缺少 Chrome Canary", names.contains("com.chrome.canary"))
        assertTrue("缺少 Firefox 穩定版", names.contains("org.mozilla.firefox"))
        assertTrue("缺少 Samsung Internet 穩定版", names.contains("com.sec.android.app.sbrowser"))
    }

    @Test
    fun chromeStableFingerprintIsTheVerifiedValueNotTheUncorroboratedLegacyOne() {
        // 對應「重要更正」註解：舊值 7C:11:C6:EE:... 在任何權威/第三方來源均查無比對，
        // 已於本次更新替換。這裡釘住正確值，防止未來不小心改回舊值或打錯。
        val chromeApp = allowlistTree["apps"].first {
            it["info"]["package_name"].asText() == "com.android.chrome"
        }
        val fingerprint = chromeApp["info"]["signatures"][0]["cert_fingerprint_sha256"].asText()
        assertEquals(
            "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83",
            fingerprint,
        )
        assertNotEquals(
            "7C:11:C6:EE:34:96:71:1D:75:26:32:AC:55:D9:6A:C4:23:D9:0F:7A:7F:47:D0:9E:26:C1:66:33:E2:B0:E7:52",
            fingerprint,
        )
    }
}
