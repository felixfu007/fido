package com.fido.credentialprovider

/**
 * 【PoC 專用設定，非產品程式碼】
 *
 * 真實產品中，購物網站的 RP ID / origin 應該從實際呼叫 Credential Manager 的網頁/瀏覽器
 * context（[androidx.credentials.provider.CallingAppInfo] 或瀏覽器傳入的 privileged origin）
 * 取得，而非寫死在 provider APP 內。
 *
 * 本 PoC 的 harness（[com.fido.credentialprovider.harness.HarnessActivity]）本身是一個原生
 * Android APP，不是瀏覽器，天生沒有「網頁 origin」可傳遞；同時 provider 本身也需要知道
 * 一個固定的 origin 來組出可驗證的 clientDataJSON，並且必須與 fido-server 的示範租戶設定
 * （`fido-server/src/main/resources/application.yml` 的 `fido.dev-seed.rp-id` /
 * `expected-origin`）完全一致，PoC 才走得通。
 *
 * 因此這裡採最簡化做法：硬編碼 rpId/origin 對齊 fido-server 開發種子租戶設定。
 * 「真實產品中，Android 端如何拿到可信的 RP ID / origin 綁定」是一個需要 systems-analyst
 * 補充規格的開放問題（見任務回報的開放問題清單），本檔案的簡化不代表該問題已有定論。
 */
object PocConfig {
    const val RP_ID: String = "shop.example.com"
    const val ORIGIN: String = "https://shop.example.com"

    /** 對齊 fido-server `application.yml` 的 `fido.dev-seed.api-key`（開發用固定 API Key）。 */
    const val DEFAULT_API_KEY: String = "dev-api-key-00000000000000000000"

    /** Android 模擬器存取宿主機 localhost 的慣例位址。 */
    const val DEFAULT_SERVER_BASE_URL: String = "http://10.0.2.2:8443"
}
