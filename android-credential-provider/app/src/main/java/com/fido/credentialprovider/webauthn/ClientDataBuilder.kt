package com.fido.credentialprovider.webauthn

/**
 * 組 WebAuthn clientDataJSON。伺服器端 `ClientDataParser` 只要求存在 `type`/`challenge`/
 * `origin` 三個欄位（純 JSON、非 CBOR，額外欄位或欄位順序不影響解析），故這裡手動組字串即可，
 * 不需要 JSON 函式庫依賴。
 *
 * origin 見 [com.fido.credentialprovider.PocConfig] 的說明與已知限制。
 */
object ClientDataBuilder {

    fun build(type: String, challengeBase64Url: String, origin: String): ByteArray {
        val json = buildString {
            append('{')
            append("\"type\":\"").append(escape(type)).append('"').append(',')
            append("\"challenge\":\"").append(escape(challengeBase64Url)).append('"').append(',')
            append("\"origin\":\"").append(escape(origin)).append('"')
            append('}')
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
