package com.fido.credentialprovider.webauthn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

/**
 * 【2026-07-23 真機除錯根因回歸測試】重現真機以 Chrome 實測踢到的 attStmt.sig／assertion
 * signature 驗證失敗根因：`androidx.credentials.CreatePublicKeyCredentialRequest`/
 * `GetPublicKeyCredentialOption` 皆帶有選填 `clientDataHash` 欄位，特權呼叫端（Chrome 等瀏覽器）
 * 會自行算好並提供這個 hash，此時 provider **必須**直接對這個 hash 簽章，不可另外自建
 * clientDataJSON 重新雜湊——即使自建的 JSON 三個值（type/challenge/origin）都對，逐 byte
 * 內容仍幾乎必然與瀏覽器最終實際使用的不同，導致簽名的 hash 與最終驗證用的 hash 不一致。
 *
 * 見 [ClientDataBuilder.resolveClientDataHash] 檔頭說明；本測試直接測試該純函式（不依賴任何
 * Android 框架類別，可在 JVM 單元測試環境執行），對齊本專案既有「framework-independent 邏輯
 * 直接測試」慣例（比照 [RegistrationResponseFieldsTest]、[CborTest]）。
 */
class ClientDataBuilderTest {

    @Test
    fun `caller-supplied clientDataHash takes precedence over self-built clientDataJson hash`() {
        // 刻意讓 selfBuiltClientDataJson 的雜湊「不等於」callerSuppliedClientDataHash，
        // 模擬瀏覽器實際使用的 clientDataJSON 與 provider 自建版本逐 byte 不同（即使值都對）
        // 的真實情境——這正是真機踢到的 bug：舊程式碼會錯誤地一律採用 sha256(selfBuiltJson)。
        val selfBuiltClientDataJson =
            """{"type":"webauthn.create","challenge":"abc","origin":"http://localhost:18081"}"""
                .toByteArray()
        val callerSuppliedClientDataHash = ByteArray(32) { 0x42 } // 刻意任意值，非 sha256(selfBuilt...)

        val resolved = ClientDataBuilder.resolveClientDataHash(
            callerSuppliedClientDataHash = callerSuppliedClientDataHash,
            selfBuiltClientDataJson = selfBuiltClientDataJson,
        )

        assertArrayEquals(
            "呼叫端有提供 clientDataHash 時必須直接使用該值，不可改用自建 JSON 的雜湊",
            callerSuppliedClientDataHash,
            resolved,
        )
        assertFalse(
            "此測試必須確保兩者原本就不同，否則測不出「有沒有錯誤地改用自建雜湊」這件事",
            resolved.contentEquals(sha256(selfBuiltClientDataJson)),
        )
    }

    @Test
    fun `falls back to self-built clientDataJson hash when caller supplies no clientDataHash`() {
        // 對應原生 App opt-in 情境（呼叫端本身不是瀏覽器，不會提供 clientDataHash，見
        // docs/origin-binding.md）：provider 是唯一可信來源，必須自建 clientDataJSON 並雜湊。
        val selfBuiltClientDataJson =
            """{"type":"webauthn.get","challenge":"xyz","origin":"android:apk-key-hash:abcd"}"""
                .toByteArray()

        val resolved = ClientDataBuilder.resolveClientDataHash(
            callerSuppliedClientDataHash = null,
            selfBuiltClientDataJson = selfBuiltClientDataJson,
        )

        assertArrayEquals(sha256(selfBuiltClientDataJson), resolved)
    }

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}
