package com.fido.credentialprovider.keystore

/**
 * 【`prod` flavor 實作】見 `src/poc/.../keystore/DiagnosticsGate.kt` 的完整背景說明
 * （[HardwareKeyManager] 為何需要這個旗標、清單項目 2 的硬體閘門語意）。
 *
 * 本檔案是 `prod` flavor 專屬的原始碼（`app/build.gradle.kts` `productFlavors { prod { ... } }`
 * 對應 `src/prod/java/...` 這個慣例路徑），與 `poc` flavor 版本**互斥**——同一次編譯只會有其中
 * 一份被納入（Gradle 依當下建置的 flavor 決定要合併哪一份原始碼進來）。`prod` 版本刻意宣告成
 * **不可變的 `val`，恆為 `false`**：正式產物完全沒有把它改成 `true` 的程式路徑（沒有
 * HarnessActivity、沒有任何 UI 開關），因此「診斷用放行軟體等級金鑰」這個能力在 `prod` APK 裡
 * 是物理上不存在的，而不只是「預設關閉但理論上可能被打開」。
 */
internal object DiagnosticsGate {
    val allowSoftwareKeyForInspection: Boolean = false
}
