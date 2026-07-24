package com.fido.credentialprovider.crossdevice

/**
 * 「使用者取消」與「本機無憑證」兩分支呼叫 `docs/api-contract.md` §3.4.E deny 端點的
 * best-effort 包裝。純邏輯，不依賴任何 Android 框架類別、不直接持有
 * [CrossDeviceServerClient]（呼叫方式透過 [Sender] 注入），可在 JVM 單元測試環境完整覆蓋
 * （比照 [CrossDeviceLoginFlow] 既有慣例）。
 *
 * **「best-effort」語意（任務規格明定）**：deny 端點只是稽核訊號，呼叫失敗（網路錯誤、伺服器
 * 錯誤碼、任何例外）**不代表**使用者的取消/無憑證這個既定本機結果有任何改變，因此
 * [reportBestEffort] **一律吞掉 [Sender.deny] 拋出的例外，不重新拋出**——呼叫端
 * （[com.fido.credentialprovider.ui.CrossDeviceLoginActivity]）永遠可以放心接著結束畫面，不必
 * 自己包 try/catch，也不會因為 deny 呼叫卡住或跳出讓人困惑的錯誤畫面。
 *
 * 這與 assertion 簽章送出失敗（[CrossDeviceLoginFlow.onSubmitFailed]）刻意不同：assertion 失敗
 * 代表登入沒有成功，必須明確告知使用者；deny 呼叫失敗不影響「使用者本來就要取消/沒有憑證」這個
 * 已經確定的結果，頂多是稽核訊號沒送達。
 */
object CrossDeviceDenyReporter {

    /** 實際呼叫 §3.4.E deny 端點的介面；容許拋出例外，由 [reportBestEffort] 負責吞掉。 */
    fun interface Sender {
        fun deny(xdevId: String, reason: CrossDeviceServerClient.DenyReason)
    }

    /**
     * 以 best-effort 方式呼叫 [sender]。成功或失敗皆不影響呼叫端後續流程——失敗時僅呼叫
     * [onFailure]（預設無動作；呼叫端可用它記 log，見 `CrossDeviceLoginActivity`），不會讓例外
     * 傳播出去。
     */
    fun reportBestEffort(
        sender: Sender,
        xdevId: String,
        reason: CrossDeviceServerClient.DenyReason,
        onFailure: (Exception) -> Unit = {},
    ) {
        try {
            sender.deny(xdevId, reason)
        } catch (e: Exception) {
            onFailure(e)
        }
    }
}
