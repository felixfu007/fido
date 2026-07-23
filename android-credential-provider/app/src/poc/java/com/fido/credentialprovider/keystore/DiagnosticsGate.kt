package com.fido.credentialprovider.keystore

/**
 * 【`poc` flavor 實作，PoC 專用診斷旗標】對應 `app/build.gradle.kts` 的
 * `productFlavors { poc { ... } }`；本檔案只存在於 `src/poc/java/...`，只有建置 `poc` flavor
 * （如 `./gradlew :app:installPocDebug`）時才會被編譯進去。`prod` flavor 使用的是
 * `src/prod/.../DiagnosticsGate.kt` 那份互斥版本（恆為 `false`、無切換入口）。
 *
 * 模擬器沒有真正硬體安全區，[HardwareKeyManager.detectSecurityLevel] 必然回報 `SOFTWARE`，
 * `generate()` 依清單項目 2 的把關邏輯會刪除該金鑰並拒絕使用——這是**正確**行為，但也代表
 * 模擬器上永遠無法產生「被允許送出」的 attestationObject，導致清單項目 3（android-key CBOR
 * 結構本身在真實 Android Keystore 產出下能否被伺服器正確解析）無法用真實裝置憑證鏈驗證，只能
 * 停留在 JVM 單元測試組出的自簽測試憑證鏈（見 `AttestationObjectBuilderTest`）。
 *
 * 開啟 [allowSoftwareKeyForInspection] 時，`HardwareKeyManager.generate()` 對軟體等級金鑰
 * **不刪除、不拒絕**，改為以 `KeyGenOutcome.Success.bypassedHardwareGateForDiagnostics=true`
 * 標記後原樣回傳，讓 [com.fido.credentialprovider.harness.HarnessActivity] 能取得模擬器
 * Keystore 真實吐出的 android-key attestation 憑證鏈，組出真正由 Android Keystore（而非 JVM
 * 自簽測試 fixture）簽發的 attestationObject，送到 fido-server 驗證 CBOR 結構是否可解析。
 * **伺服器端的硬體閘門與憑證鏈信任驗證完全不受此旗標影響**——`fido.attestation.mode=real`
 * 下伺服器仍會依實際憑證鏈判斷結果（模擬器產生的憑證鏈預期會被伺服器擋下，見
 * docs/android-poc-checklist.md 第 2 節）。
 *
 * 只能透過 [com.fido.credentialprovider.harness.HarnessActivity] 的診斷用開關手動開啟；
 * 正式 `CreatePasskeyActivity` 的一般路徑不會、也不應該主動開啟它。
 */
internal object DiagnosticsGate {
    @Volatile
    var allowSoftwareKeyForInspection: Boolean = false
}
