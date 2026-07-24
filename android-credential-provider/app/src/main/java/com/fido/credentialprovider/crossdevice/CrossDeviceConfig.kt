package com.fido.credentialprovider.crossdevice

/**
 * App 直連 fido-server 的 base URL 設定（情境三跨裝置 QR 登入專屬新能力——現有
 * `CreatePasskeyActivity`/`GetPasskeyActivity` 完全不直連 fido-server，見設計文件
 * `docs/decisions/qr-cross-device-login-design.md` 4.3「App 端 HTTPS client…這是 App 最大的
 * 新增能力」）。
 *
 * 比照 [com.fido.credentialprovider.PocConfig] 既有慣例：單一常數，非逐環境設定檔機制。
 * 多環境/正式部署的網域與憑證由 devops-engineer 決定（不在本次任務範圍，見 CLAUDE.md 團隊分工），
 * 目前指向 PoC/開發環境慣用位址——比照 `harness/FidoServerClient` 與
 * `harness/res/layout/activity_harness.xml` 的 `serverBaseUrlInput` 預設值
 * `http://10.0.2.2:8443`（10.0.2.2 是 Android 模擬器存取宿主機 localhost 的慣例位址）。
 * fido-server 骨架目前未啟用 TLS（見 `network_security_config.xml` 說明），故此為明文 HTTP；
 * 正式環境需改為真正的 HTTPS URL 並移除/收斂 `network_security_config.xml` 的明文放行清單
 * （待 devops-engineer 部署時處理，非本次任務範圍）。
 *
 * 【與 deep link host 的關係，務必留意】本常數才是 App 呼叫 fido-server 的**唯一**權威位置。
 * 見 [CrossDeviceDeepLinkParser] 檔頭說明：QR/deep link 內的 host 一律被忽略、不採信，
 * 正是為了不讓一個被竄改的 QR 把 App 導向此常數以外的任何伺服器（設計文件 3.1 第 3 點）。
 */
object CrossDeviceConfig {
    const val FIDO_SERVER_BASE_URL: String = "http://10.0.2.2:8443"
}
