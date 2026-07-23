package com.fido.credentialprovider.webauthn

import java.security.MessageDigest

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

    /**
     * 【2026-07-23 真機除錯根因修正】決定簽章實際要覆蓋的 clientDataHash。
     *
     * `androidx.credentials.CreatePublicKeyCredentialRequest`/`GetPublicKeyCredentialOption`
     * 都帶有選填的 `clientDataHash` 欄位：特權呼叫端（例如 Chrome 這類瀏覽器）身為 WebAuthn
     * 規範定義的「client」本身，才是唯一有資格建構 CollectedClientData／clientDataJSON 的一方，
     * 因此會自行算好 hash 傳進來，並預期 provider（=authenticator 角色）直接對這個 hash 簽章。
     *
     * 真機以 Chrome 實測（2026-07-23）證實：若 provider 忽略呼叫端提供的 hash、自行組一份
     * clientDataJSON 重新雜湊來簽，即使 type/challenge/origin 三個「值」都對（因為兩邊各自
     * 用的都是真實值），逐 byte 內容仍幾乎必然不同（欄位順序、空白、是否含 crossOrigin 等），
     * 使簽的 hash 與瀏覽器最終實際吐給網頁 JS／送給 server 驗證用的 hash 不一致——這類不一致
     * 會逃過只檢查「值」而非逐 byte 內容的 type/challenge/origin 檢查，只在 attStmt.sig／
     * assertion signature 密碼學驗證這一關才會現形（見 fido-server
     * `RealAttestationStatementVerifier`/`RealAssertionSignatureVerifier`）。
     *
     * 呼叫端未提供（例如原生 App opt-in 情境，呼叫端本身不是瀏覽器，見
     * docs/origin-binding.md）時，才落回本專案自建 clientDataJSON 重新雜湊的路徑，因為那個
     * 情境下沒有其他權威來源可用。
     *
     * @param callerSuppliedClientDataHash 呼叫端（Credential Manager 請求物件）提供的 hash，
     *   可能為 null
     * @param selfBuiltClientDataJson 本 provider 依 requestJson + 解析出的 origin 自建的
     *   clientDataJSON（僅在呼叫端未提供 hash 時才會被拿來雜湊使用）
     */
    fun resolveClientDataHash(
        callerSuppliedClientDataHash: ByteArray?,
        selfBuiltClientDataJson: ByteArray,
    ): ByteArray = callerSuppliedClientDataHash ?: sha256(selfBuiltClientDataJson)

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
