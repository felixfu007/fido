package com.fido.credentialprovider.webauthn

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
 * 模擬器、純位元組轉換的部分：[OriginResolver.apkKeyHashOrigin]。比照
 * `AttestationObjectBuilderTest` 的模式（同套件內驗證框架無關的位元組層級邏輯）。
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
}
