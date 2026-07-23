package com.fido.credentialprovider

/**
 * 【已縮小為單一已知簡化項，非整體 PoC 專用設定】
 *
 * origin-binding.md 定案前，本物件同時硬編碼 `RP_ID` 與 `ORIGIN` 供 ceremony 使用；
 * `ORIGIN` 已移除——`CreatePasskeyActivity` / `GetPasskeyActivity` 現改用
 * [com.fido.credentialprovider.webauthn.OriginResolver] 從呼叫方 `CallingAppInfo` 動態解析
 * 並驗證 origin（見 `docs/origin-binding.md` 第 6 節），不再有任何寫死 origin 路徑。
 *
 * 僅剩 [RP_ID] 仍被使用，且用途限縮為 [com.fido.credentialprovider.FidoCredentialProviderService]
 * 列出「本機已註冊 passkey entry」時的過濾條件（`onBeginGetCredentialRequest` ->
 * `buildEntriesForOption`）——這是與 origin 安全性**無關**的 UX 簡化：本 PoC 單一租戶，
 * 直接列出本機所有已註冊 credential；正式多租戶產品應改依
 * `BeginGetPublicKeyCredentialOption.requestJson` 內的 `rpId` 過濾（該處程式碼註解已標註此
 * 已知缺口）。ceremony 本身的 rpId 一律以請求 JSON 的 `rp.id` /（assertion 情境）本機
 * `LocalCredentialStore` 紀錄之值為準，不會 fallback 到這裡。
 */
object PocConfig {
    const val RP_ID: String = "shop.example.com"
}
