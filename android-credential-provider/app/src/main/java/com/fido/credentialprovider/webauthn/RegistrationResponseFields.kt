package com.fido.credentialprovider.webauthn

import java.util.Base64

/**
 * 【2026-07-23 真機除錯新增】組出交還系統 Credential Manager 的 `CreatePublicKeyCredentialResponse`
 * JSON 的 `response` 欄位內容，對齊 Chromium `components/webauthn/json/value_conversions.cc`
 * `MakeCredentialResponseFromValue()`（真機透過 Chrome 實際跑註冊 ceremony 時，經 Chromium
 * 原始碼查證確認的解析邏輯，而非臆測）：
 *
 *  - `attestationObject`：必要（本專案既有欄位）。
 *  - `authenticatorData`：必要，且 Chrome 會與其自行解碼 `attestationObject` CBOR 內嵌的
 *    `authData` bytes 逐 byte 比對，必須完全一致——不可自行「重新產生」一份，必須是同一份
 *    bytes（見 [com.fido.credentialprovider.ui.CreatePasskeyActivity.performRegistration]
 *    呼叫端在組 `attestationObject` **之前**就先產生好的 `authenticatorData`，兩處共用同一個
 *    參考，而非各自重算）。
 *  - `publicKeyAlgorithm`：必要，且 Chrome 會與其自行從 `authData` 內 COSE_Key 解出的演算法
 *    比對是否相等，必須與 [CoseKeyEncoder.encodeEcP256] 實際寫入 COSE_Key `alg`(3) 欄位的值
 *    一致（本專案恆為 [CoseKeyEncoder.COSE_ALG_ES256] = -7）。
 *  - `publicKey`：對 ES256（本專案唯一使用的演算法）**必要**，格式為 SubjectPublicKeyInfo
 *    （X.509）DER，base64url 編碼。Android `ECPublicKey.getEncoded()`（來自
 *    `X509EncodedKeySpec`，見 `HardwareKeyManager.generate()` 重建 publicKey 的方式）本身即為
 *    此格式，不需額外轉換。Chrome 會與其自行從 COSE_Key 換算出的 DER 比對是否相等。
 *
 * 刻意獨立於 [com.fido.credentialprovider.ui.CreatePasskeyActivity]（該類別依賴
 * `android.util.Base64`/`org.json.JSONObject` 等 Android 框架 stub，在純 JVM 單元測試環境會
 * 拋 `RuntimeException: Stub!`）：本檔案只用 JDK `java.util.Base64`，可直接被
 * `RegistrationResponseFieldsTest` 驗證欄位正確性，呼叫端 Activity 只需把這裡算好的欄位塞進
 * `JSONObject`（無邏輯的黏著層，比照 `SetupStatusSupport`/`SetupStatusActivity` 的既有分工）。
 */
object RegistrationResponseFields {

    data class Fields(
        val clientDataJsonB64Url: String,
        val attestationObjectB64Url: String,
        val authenticatorDataB64Url: String,
        val publicKeyB64Url: String,
        val publicKeyAlgorithm: Int,
    )

    fun from(
        clientDataJson: ByteArray,
        attestationObject: ByteArray,
        authenticatorData: ByteArray,
        publicKeyDer: ByteArray,
        publicKeyAlgorithm: Int = CoseKeyEncoder.COSE_ALG_ES256,
    ): Fields = Fields(
        clientDataJsonB64Url = b64UrlEncode(clientDataJson),
        attestationObjectB64Url = b64UrlEncode(attestationObject),
        authenticatorDataB64Url = b64UrlEncode(authenticatorData),
        publicKeyB64Url = b64UrlEncode(publicKeyDer),
        publicKeyAlgorithm = publicKeyAlgorithm,
    )

    /**
     * RFC 4648 §5 base64url，無 padding——與本專案在 Android 執行期使用的
     * `Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING` 輸出位元組相同（皆為
     * base64url 字母表、不補 `=`），也是 Chromium `base::Base64UrlDecode` 以
     * `DISALLOW_PADDING` 解碼時唯一接受的形式。
     */
    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
