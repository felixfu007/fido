package com.fido.credentialprovider.webauthn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 【2026-07-23 真機除錯新增】驗證 [RegistrationResponseFields.from] 產出的欄位滿足 Chrome 原生層
 * `Fido2CredentialRequest.MakeCredentialResponseFromJson`（`components/webauthn/json/
 * value_conversions.cc` `MakeCredentialResponseFromValue()`，經 Chromium 原始碼查證，見
 * [RegistrationResponseFields] 檔頭說明）在 `response` 物件內要求的三個先前缺漏欄位：
 * `authenticatorData`、`publicKeyAlgorithm`、`publicKey`（外加既有的 `attestationObject`/
 * `clientDataJSON`），逐一比對：
 *  1. 欄位存在（回傳的 [RegistrationResponseFields.Fields] 每個屬性都不可為 null——Kotlin
 *     型別系統已保證這點，重點測「有值且值正確」）。
 *  2. base64url 編碼正確：無 padding、使用 `-`/`_`（而非標準 base64 的 `+`/`/`），且能被
 *     Chromium `base::Base64UrlDecode(..., DISALLOW_PADDING)` 接受的同一種編碼方式解回原始
 *     bytes（本測試用 JDK `java.util.Base64` URL decoder 驗證往返，等價於 Chromium 那端的
 *     解碼規則）。
 *  3. `publicKeyAlgorithm` 數值正確：對齊 [CoseKeyEncoder.COSE_ALG_ES256]（-7，ES256），
 *     即本專案 COSE_Key `alg`(3) 欄位實際寫入的同一個值——Chrome 會拿這個數字跟它自己解碼
 *     `attestationObject` 內 COSE_Key 得到的演算法比對，兩者不符會直接判定回應無效。
 */
class RegistrationResponseFieldsTest {

    private val urlDecoder = Base64.getUrlDecoder()

    @Test
    fun `all four byte fields are present and base64url-nopad encoded`() {
        val clientDataJson = "client-data-json-bytes".toByteArray()
        val attestationObject = byteArrayOf(0x01, 0x02, 0x03, -1, -2, -3) // 含高位元組，易觸發 +/= 字元
        // 首 byte 0xFF 的最高 6 bits 為 111111，標準 base64 字母表第 63 個字元固定是 '/'，
        // 不論後續 bytes 為何都保證觸發——比起挑特定數值組合更不容易因編碼細節而巧合落空。
        val authenticatorData = byteArrayOf(0xFF.toByte(), 0x20, 0x30, 0x3E, 0x3F)
        val publicKeyDer = "fake-spki-der-bytes-for-test".toByteArray()

        val fields = RegistrationResponseFields.from(
            clientDataJson = clientDataJson,
            attestationObject = attestationObject,
            authenticatorData = authenticatorData,
            publicKeyDer = publicKeyDer,
        )

        // ---- 1. 欄位存在且往返解碼後與原始 bytes 完全一致 ----
        assertArrayEqualsAfterUrlDecode(clientDataJson, fields.clientDataJsonB64Url)
        assertArrayEqualsAfterUrlDecode(attestationObject, fields.attestationObjectB64Url)
        assertArrayEqualsAfterUrlDecode(authenticatorData, fields.authenticatorDataB64Url)
        assertArrayEqualsAfterUrlDecode(publicKeyDer, fields.publicKeyB64Url)

        // ---- 2. 編碼格式：無 padding、無標準 base64 專用字元 ----
        listOf(
            fields.clientDataJsonB64Url,
            fields.attestationObjectB64Url,
            fields.authenticatorDataB64Url,
            fields.publicKeyB64Url,
        ).forEach { encoded ->
            assertFalse("base64url 輸出不得含 padding '='：$encoded", encoded.contains("="))
            assertFalse("base64url 輸出不得含標準 base64 的 '+'：$encoded", encoded.contains("+"))
            assertFalse("base64url 輸出不得含標準 base64 的 '/'：$encoded", encoded.contains("/"))
        }

        // 挑一個必然會在標準 base64 產生 '+'/'/' 的 bytes 組合，實際證明我們用的是 url-safe 字母表
        // 而非只是「這次剛好沒用到」。
        val standardBase64Encoded = Base64.getEncoder().encodeToString(authenticatorData)
        assertTrue(
            "測試前提：這組 bytes 在標準 base64 字母表下應含 '+' 或 '/'（否則測試沒有實際驗證到字母表差異）",
            standardBase64Encoded.contains("+") || standardBase64Encoded.contains("/"),
        )
    }

    @Test
    fun `publicKeyAlgorithm defaults to ES256 (-7), matching CoseKeyEncoder`() {
        val fields = RegistrationResponseFields.from(
            clientDataJson = byteArrayOf(1),
            attestationObject = byteArrayOf(2),
            authenticatorData = byteArrayOf(3),
            publicKeyDer = byteArrayOf(4),
        )

        assertEquals(CoseKeyEncoder.COSE_ALG_ES256, fields.publicKeyAlgorithm)
        assertEquals(-7, fields.publicKeyAlgorithm)
    }

    @Test
    fun `publicKeyAlgorithm can be overridden explicitly if a different algorithm is ever used`() {
        val fields = RegistrationResponseFields.from(
            clientDataJson = byteArrayOf(1),
            attestationObject = byteArrayOf(2),
            authenticatorData = byteArrayOf(3),
            publicKeyDer = byteArrayOf(4),
            publicKeyAlgorithm = -8, // EdDSA，僅示範參數確實會被使用，非本專案目前實際路徑
        )

        assertEquals(-8, fields.publicKeyAlgorithm)
    }

    @Test
    fun `authenticatorData field is byte-identical to the exact bytes embedded in attestationObject`() {
        // 對齊 Chrome 端的比對邏輯：response.authenticatorData 必須與其自行解碼 attestationObject
        // CBOR 內 authData 得到的 bytes 逐 byte相等，因此呼叫端必須傳「同一份」bytes 而非另外
        // 重新產生一份——這裡驗證 RegistrationResponseFields 忠實地把傳入的 authenticatorData
        // 原封不動編碼，沒有做任何轉換/重算。
        val authenticatorData = ByteArray(37) { it.toByte() } // 模擬固定版面 authData 的長度級距
        val attestationObjectContainingSameAuthData = AttestationObjectBuilder.build(
            authenticatorData = authenticatorData,
            attStmtSignature = byteArrayOf(9, 9, 9),
            certificateChain = emptyList(),
        )

        val fields = RegistrationResponseFields.from(
            clientDataJson = byteArrayOf(1),
            attestationObject = attestationObjectContainingSameAuthData,
            authenticatorData = authenticatorData,
            publicKeyDer = byteArrayOf(4),
        )

        assertArrayEqualsAfterUrlDecode(authenticatorData, fields.authenticatorDataB64Url)
    }

    private fun assertArrayEqualsAfterUrlDecode(expected: ByteArray, actualB64Url: String) {
        val decoded = urlDecoder.decode(actualB64Url)
        org.junit.Assert.assertArrayEquals(expected, decoded)
    }
}
