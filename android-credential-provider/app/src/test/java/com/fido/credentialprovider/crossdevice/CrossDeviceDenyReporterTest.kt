package com.fido.credentialprovider.crossdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrossDeviceDenyReporter] 涵蓋任務規格的「best-effort」語意：deny 端點呼叫成功時把
 * xdevId/reason 原樣轉交給 [CrossDeviceDenyReporter.Sender]；呼叫失敗（`Sender` 拋例外）時
 * 例外**不得**傳播出 [CrossDeviceDenyReporter.reportBestEffort]，只轉交給 `onFailure`。
 * 純邏輯，不依賴任何 Android 框架類別或真實網路連線，可在 JVM 單元測試環境完整覆蓋
 * （比照 [CrossDeviceLoginFlowTest] 既有慣例）。
 */
class CrossDeviceDenyReporterTest {

    @Test
    fun `successful sender call receives the exact xdevId and reason — user cancelled`() {
        var receivedXdevId: String? = null
        var receivedReason: CrossDeviceServerClient.DenyReason? = null

        CrossDeviceDenyReporter.reportBestEffort(
            sender = { xdevId, reason ->
                receivedXdevId = xdevId
                receivedReason = reason
            },
            xdevId = "xdev-abc",
            reason = CrossDeviceServerClient.DenyReason.USER_CANCELLED,
        )

        assertEquals("xdev-abc", receivedXdevId)
        assertEquals(CrossDeviceServerClient.DenyReason.USER_CANCELLED, receivedReason)
    }

    @Test
    fun `successful sender call receives the exact xdevId and reason — no credential`() {
        var receivedReason: CrossDeviceServerClient.DenyReason? = null

        CrossDeviceDenyReporter.reportBestEffort(
            sender = { _, reason -> receivedReason = reason },
            xdevId = "xdev-def",
            reason = CrossDeviceServerClient.DenyReason.NO_CREDENTIAL,
        )

        assertEquals(CrossDeviceServerClient.DenyReason.NO_CREDENTIAL, receivedReason)
    }

    @Test
    fun `sender exception (network error) does not propagate out of reportBestEffort`() {
        // best-effort 核心語意：deny 呼叫失敗（模擬網路錯誤/伺服器錯誤碼導致的例外）
        // 不應讓呼叫端需要自己包 try/catch，也不應中斷呼叫端接下來要做的事（結束畫面）。
        var onFailureCalled = false

        CrossDeviceDenyReporter.reportBestEffort(
            sender = { _, _ -> throw java.io.IOException("network down") },
            xdevId = "xdev-abc",
            reason = CrossDeviceServerClient.DenyReason.USER_CANCELLED,
            onFailure = { onFailureCalled = true },
        )

        // 沒有例外從上面那段呼叫拋出來 = 測試方法本身能跑完，這裡再額外確認 onFailure 有被通知到。
        assertTrue(onFailureCalled)
    }

    @Test
    fun `sender exception with default onFailure (no callback supplied) is silently swallowed`() {
        // 呼叫端可以完全不傳 onFailure（例如只想 fire-and-forget），例外仍必須被吞掉。
        CrossDeviceDenyReporter.reportBestEffort(
            sender = { _, _ -> throw RuntimeException("boom") },
            xdevId = "xdev-abc",
            reason = CrossDeviceServerClient.DenyReason.NO_CREDENTIAL,
        )
        // 能執行到這行代表例外沒有往外拋。
        assertTrue(true)
    }

    @Test
    fun `onFailure receives the original exception instance`() {
        val original = IllegalStateException("XDEV_SESSION_NOT_FOUND")
        var captured: Exception? = null

        CrossDeviceDenyReporter.reportBestEffort(
            sender = { _, _ -> throw original },
            xdevId = "xdev-abc",
            reason = CrossDeviceServerClient.DenyReason.USER_CANCELLED,
            onFailure = { e -> captured = e },
        )

        assertEquals(original, captured)
    }

    @Test
    fun `onFailure is not invoked when sender succeeds`() {
        var onFailureCalled = false

        CrossDeviceDenyReporter.reportBestEffort(
            sender = { _, _ -> /* no-op success */ },
            xdevId = "xdev-abc",
            reason = CrossDeviceServerClient.DenyReason.USER_CANCELLED,
            onFailure = { onFailureCalled = true },
        )

        assertFalse(onFailureCalled)
    }
}
