package com.fido.credentialprovider.crossdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrossDeviceLoginFlow] 涵蓋設計文件 4.2 步驟 3–7 的狀態轉換：0/1/多筆本機憑證分流、
 * 確認/取消兩個分支、簽章與送出結果的成功/失敗轉換。純邏輯，不依賴任何 Android 框架類別，可在
 * JVM 單元測試環境完整覆蓋。
 */
class CrossDeviceLoginFlowTest {

    private val claim = CrossDeviceLoginFlow.ClaimContext(
        xdevId = "xdev-abc",
        rpId = "shop.example.com",
        origin = "https://shop.example.com",
        tenantDisplayName = "Example Shop",
        challengeB64Url = "Y2hhbGxlbmdl",
        verificationCode = "38-421",
    )

    @Test
    fun `invalid deep link produces InvalidLink state carrying the reason`() {
        val state = CrossDeviceLoginFlow.onInvalidLink("MALFORMED_XDEV_LINK")

        assertTrue(state is CrossDeviceLoginFlow.UiState.InvalidLink)
        assertEquals("MALFORMED_XDEV_LINK", (state as CrossDeviceLoginFlow.UiState.InvalidLink).reason)
    }

    @Test
    fun `claim endpoint failure produces ClaimFailed state carrying the reason`() {
        val state = CrossDeviceLoginFlow.onClaimFailed("XDEV_SESSION_EXPIRED")

        assertTrue(state is CrossDeviceLoginFlow.UiState.ClaimFailed)
        assertEquals("XDEV_SESSION_EXPIRED", (state as CrossDeviceLoginFlow.UiState.ClaimFailed).reason)
    }

    @Test
    fun `zero local credentials for rpId produces NoCredentialForRp`() {
        val state = CrossDeviceLoginFlow.onClaimSucceeded(claim, emptyList())

        assertTrue(state is CrossDeviceLoginFlow.UiState.NoCredentialForRp)
        state as CrossDeviceLoginFlow.UiState.NoCredentialForRp
        assertEquals(claim.tenantDisplayName, state.tenantDisplayName)
        assertEquals(claim.rpId, state.rpId)
    }

    @Test
    fun `exactly one local credential goes straight to AwaitingConfirmation`() {
        val state = CrossDeviceLoginFlow.onClaimSucceeded(claim, listOf("cred-1"))

        assertTrue(state is CrossDeviceLoginFlow.UiState.AwaitingConfirmation)
        state as CrossDeviceLoginFlow.UiState.AwaitingConfirmation
        assertEquals(claim, state.claim)
        assertEquals("cred-1", state.credentialId)
    }

    @Test
    fun `multiple local credentials produce SelectingCredential with all ids preserved`() {
        val state = CrossDeviceLoginFlow.onClaimSucceeded(claim, listOf("cred-1", "cred-2", "cred-3"))

        assertTrue(state is CrossDeviceLoginFlow.UiState.SelectingCredential)
        state as CrossDeviceLoginFlow.UiState.SelectingCredential
        assertEquals(claim, state.claim)
        assertEquals(listOf("cred-1", "cred-2", "cred-3"), state.credentialIds)
    }

    @Test
    fun `choosing a credential from the selector transitions to AwaitingConfirmation with that id`() {
        val state = CrossDeviceLoginFlow.onCredentialChosen(claim, "cred-2")

        assertTrue(state is CrossDeviceLoginFlow.UiState.AwaitingConfirmation)
        state as CrossDeviceLoginFlow.UiState.AwaitingConfirmation
        assertEquals("cred-2", state.credentialId)
    }

    @Test
    fun `user cancelling the confirmation screen transitions to Denied`() {
        val state = CrossDeviceLoginFlow.onUserCancelled()

        assertEquals(CrossDeviceLoginFlow.UiState.Denied, state)
    }

    @Test
    fun `user confirming transitions to SigningAndSubmitting`() {
        val state = CrossDeviceLoginFlow.onUserConfirmed()

        assertEquals(CrossDeviceLoginFlow.UiState.SigningAndSubmitting, state)
    }

    @Test
    fun `successful submit without proximity mismatch produces Confirmed(false)`() {
        val state = CrossDeviceLoginFlow.onSubmitSucceeded(proximityMismatch = false)

        assertTrue(state is CrossDeviceLoginFlow.UiState.Confirmed)
        assertEquals(false, (state as CrossDeviceLoginFlow.UiState.Confirmed).proximityMismatch)
    }

    @Test
    fun `successful submit with proximity mismatch still confirms but flags the mismatch`() {
        // S2 拍板：proximity 只警示不阻擋——不一致仍是成功狀態(Confirmed)，只是多帶警示旗標。
        val state = CrossDeviceLoginFlow.onSubmitSucceeded(proximityMismatch = true)

        assertTrue(state is CrossDeviceLoginFlow.UiState.Confirmed)
        assertEquals(true, (state as CrossDeviceLoginFlow.UiState.Confirmed).proximityMismatch)
    }

    @Test
    fun `submit failure produces SubmitFailed state carrying the reason`() {
        val state = CrossDeviceLoginFlow.onSubmitFailed("ASSERTION_INVALID")

        assertTrue(state is CrossDeviceLoginFlow.UiState.SubmitFailed)
        assertEquals("ASSERTION_INVALID", (state as CrossDeviceLoginFlow.UiState.SubmitFailed).reason)
    }

    @Test
    fun `full happy path chain — claim, single credential, confirm, submit success`() {
        var state: CrossDeviceLoginFlow.UiState = CrossDeviceLoginFlow.onClaimSucceeded(claim, listOf("cred-1"))
        assertTrue(state is CrossDeviceLoginFlow.UiState.AwaitingConfirmation)

        state = CrossDeviceLoginFlow.onUserConfirmed()
        assertEquals(CrossDeviceLoginFlow.UiState.SigningAndSubmitting, state)

        state = CrossDeviceLoginFlow.onSubmitSucceeded(proximityMismatch = false)
        assertTrue(state is CrossDeviceLoginFlow.UiState.Confirmed)
    }

    @Test
    fun `full cancel path chain — claim, multiple credentials, choose, then cancel`() {
        var state: CrossDeviceLoginFlow.UiState =
            CrossDeviceLoginFlow.onClaimSucceeded(claim, listOf("cred-1", "cred-2"))
        assertTrue(state is CrossDeviceLoginFlow.UiState.SelectingCredential)

        state = CrossDeviceLoginFlow.onCredentialChosen(claim, "cred-2")
        assertTrue(state is CrossDeviceLoginFlow.UiState.AwaitingConfirmation)

        state = CrossDeviceLoginFlow.onUserCancelled()
        assertEquals(CrossDeviceLoginFlow.UiState.Denied, state)
    }

    // --- api-contract.md §3.4.E / D18：deny 端點 reason 判斷（denyReasonFor） ---

    @Test
    fun `denyReasonFor Denied state maps to USER_CANCELLED`() {
        val state = CrossDeviceLoginFlow.onUserCancelled()

        assertEquals(
            CrossDeviceServerClient.DenyReason.USER_CANCELLED,
            CrossDeviceLoginFlow.denyReasonFor(state),
        )
    }

    @Test
    fun `denyReasonFor NoCredentialForRp state maps to NO_CREDENTIAL`() {
        val state = CrossDeviceLoginFlow.onClaimSucceeded(claim, emptyList())

        assertEquals(
            CrossDeviceServerClient.DenyReason.NO_CREDENTIAL,
            CrossDeviceLoginFlow.denyReasonFor(state),
        )
    }

    @Test
    fun `denyReasonFor other states returns null — no deny call is triggered`() {
        assertEquals(null, CrossDeviceLoginFlow.denyReasonFor(CrossDeviceLoginFlow.UiState.Loading))
        assertEquals(null, CrossDeviceLoginFlow.denyReasonFor(CrossDeviceLoginFlow.onClaimFailed("X")))
        assertEquals(
            null,
            CrossDeviceLoginFlow.denyReasonFor(CrossDeviceLoginFlow.onClaimSucceeded(claim, listOf("cred-1"))),
        )
        assertEquals(null, CrossDeviceLoginFlow.denyReasonFor(CrossDeviceLoginFlow.onUserConfirmed()))
        assertEquals(
            null,
            CrossDeviceLoginFlow.denyReasonFor(CrossDeviceLoginFlow.onSubmitSucceeded(proximityMismatch = false)),
        )
        assertEquals(null, CrossDeviceLoginFlow.denyReasonFor(CrossDeviceLoginFlow.onSubmitFailed("X")))
    }
}
