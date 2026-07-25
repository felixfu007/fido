package com.fido.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.config.FidoProperties;
import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.CrossDeviceSession;
import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.CrossDeviceSessionStatus;
import com.fido.server.dto.request.AuthenticationResultRequest;
import com.fido.server.dto.request.CrossDeviceDenyRequest;
import com.fido.server.dto.request.CrossDeviceResultRequest;
import com.fido.server.dto.request.CrossDeviceSessionCreateRequest;
import com.fido.server.dto.response.AuthenticationResultResponse;
import com.fido.server.dto.response.CrossDeviceClaimResponse;
import com.fido.server.dto.response.CrossDeviceDenyResponse;
import com.fido.server.dto.response.CrossDeviceResultResponse;
import com.fido.server.dto.response.CrossDeviceSessionCreateResponse;
import com.fido.server.dto.response.CrossDeviceStatusResponse;
import com.fido.server.exception.ApiException;
import com.fido.server.exception.ErrorCode;
import com.fido.server.repository.AuthChallengeRepository;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.CrossDeviceSessionRepository;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.TenantRepository;
import com.fido.server.service.AuditService;
import com.fido.server.service.AuthenticationService;
import com.fido.server.service.ChallengeService;
import com.fido.server.service.CrossDeviceLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 對 {@link CrossDeviceLoginService} 的狀態機/proximity/JWT 守衛式領取邏輯做 Mockito 單元測試
 * （比照 {@code JwtServiceTest} 的純 mock 風格）。端對端（真實 HTTP + 真實密碼學簽章）happy
 * path 見 {@link CrossDeviceLoginFlowTest}；本類別聚焦這裡更方便構造的邊界情況（session 逾時、
 * 狀態機非法轉移、跨租戶隔離、{@code consumeConfirmedJwt} 守衛式 UPDATE 搶輸的情境），這些若只靠
 * HTTP 端對端測試會需要真的等待 120 秒或另外注入 Clock 抽象，不划算。真實資料庫併發（多執行緒
 * 對同一 xdevId 搶 consumeConfirmedJwt）見 {@code JpaPersistenceH2FlowTest}。
 */
class CrossDeviceLoginServiceTest {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private ChallengeService challengeService;
    private AuthChallengeRepository authChallengeRepository;
    private CrossDeviceSessionRepository crossDeviceSessionRepository;
    private TenantRepository tenantRepository;
    private AuthenticationService authenticationService;
    private FidoCredentialRepository fidoCredentialRepository;
    private BoundDeviceRepository boundDeviceRepository;
    private AuditService auditService;
    private CrossDeviceLoginService service;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        FidoProperties properties = new FidoProperties();
        properties.getCrossDevice().setTtlSeconds(120);
        properties.getCrossDevice().setAppLinkHost("fido-app.test.internal");

        challengeService = mock(ChallengeService.class);
        authChallengeRepository = mock(AuthChallengeRepository.class);
        crossDeviceSessionRepository = mock(CrossDeviceSessionRepository.class);
        tenantRepository = mock(TenantRepository.class);
        authenticationService = mock(AuthenticationService.class);
        fidoCredentialRepository = mock(FidoCredentialRepository.class);
        boundDeviceRepository = mock(BoundDeviceRepository.class);
        auditService = mock(AuditService.class);

        service = new CrossDeviceLoginService(properties, challengeService, authChallengeRepository,
                crossDeviceSessionRepository, tenantRepository, authenticationService, fidoCredentialRepository,
                boundDeviceRepository, auditService, new ObjectMapper());

        tenant = new Tenant();
        tenant.setTenantId(1L);
        tenant.setName("Demo Shop");
        tenant.setRpId("shop.example.com");
        tenant.setExpectedOrigin("[\"https://shop.example.com\"]");

        // save() 回傳原封不動傳入的物件（模擬 in-memory repository 行為）。
        when(crossDeviceSessionRepository.save(any(CrossDeviceSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------
    // 端點 A：createSession
    // ------------------------------------------------------------------

    @Test
    void createSessionBuildsPendingSessionAndReturnsQrPayload() {
        AuthChallenge challenge = new AuthChallenge();
        challenge.setChallengePk(10L);
        challenge.setCeremonyId("auth_xdev1");
        challenge.setChallenge(new byte[]{1, 2, 3, 4});
        challenge.setExpiresAt(Instant.now().plusSeconds(120));
        when(challengeService.create(eq(tenant), isNull(), any(), eq(120))).thenReturn(challenge);

        CrossDeviceSessionCreateResponse response =
                service.createSession(tenant, new CrossDeviceSessionCreateRequest("203.0.113.5"));

        assertThat(response.expiresIn()).isEqualTo(120);
        assertThat(response.qrUrl()).isEqualTo("https://fido-app.test.internal/x/" + response.xdevId());
        assertThat(response.verificationCode()).matches("\\d{2}-\\d{3}");

        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_SESSION_CREATED"), any(), anyMap());
        verify(crossDeviceSessionRepository).save(any(CrossDeviceSession.class));
    }

    // ------------------------------------------------------------------
    // 端點 B：claim
    // ------------------------------------------------------------------

    @Test
    void claimUnknownXdevIdThrowsNotFound() {
        when(crossDeviceSessionRepository.findByXdevId("no-such-xdev")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim("no-such-xdev", "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_NOT_FOUND);
    }

    @Test
    void claimExpiredSessionMarksExpiredAndThrows() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().minusSeconds(5));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.claim("xdev1", "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_EXPIRED);

        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.EXPIRED);
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_EXPIRED"), any(), anyMap());
    }

    @Test
    void claimOnAlreadyScannedSessionThrowsInvalidState() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.SCANNED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.claim("xdev1", "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
    }

    @Test
    void claimSuccessTransitionsToScannedAndReturnsAuthoritativeContext() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setVerificationCode("12-345");
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));

        AuthChallenge challenge = new AuthChallenge();
        challenge.setChallengePk(10L);
        challenge.setCeremonyId("auth_xdev1");
        challenge.setChallenge(new byte[]{9, 9, 9});
        when(authChallengeRepository.findByChallengePk(10L)).thenReturn(Optional.of(challenge));

        CrossDeviceClaimResponse response = service.claim("xdev1", "8.8.8.8");

        assertThat(response.rpId()).isEqualTo("shop.example.com");
        assertThat(response.origin()).isEqualTo("https://shop.example.com");
        assertThat(response.tenantDisplayName()).isEqualTo("Demo Shop");
        assertThat(response.challenge()).isEqualTo(B64URL.encodeToString(new byte[]{9, 9, 9}));
        assertThat(response.verificationCode()).isEqualTo("12-345");

        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.SCANNED);
        assertThat(session.getPhoneIp()).isEqualTo("8.8.8.8");
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_CLAIMED"), any(), anyMap());
    }

    // ------------------------------------------------------------------
    // 端點 C：submitResult
    // ------------------------------------------------------------------

    @Test
    void submitResultOnPendingSessionThrowsInvalidState() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.submitResult("xdev1", dummyResultRequest(), "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
        verify(authenticationService, never()).verifyResult(any(), any(), any());
    }

    @Test
    void submitResultOnExpiredScannedSessionThrowsExpired() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().minusSeconds(1));
        session.setStatus(CrossDeviceSessionStatus.SCANNED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.submitResult("xdev1", dummyResultRequest(), "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_EXPIRED);
    }

    @Test
    void submitResultPropagatesCryptoFailureWithoutMutatingSessionState() {
        CrossDeviceSession session = scannedSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        AuthChallenge challenge = new AuthChallenge();
        challenge.setChallengePk(10L);
        challenge.setCeremonyId("auth_xdev1");
        when(authChallengeRepository.findByChallengePk(10L)).thenReturn(Optional.of(challenge));

        when(authenticationService.verifyResult(eq(tenant), any(AuthenticationResultRequest.class), eq(List.of("xdev"))))
                .thenThrow(new ApiException(ErrorCode.ASSERTION_INVALID, "bad signature"));

        assertThatThrownBy(() -> service.submitResult("xdev1", dummyResultRequest(), "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSERTION_INVALID);

        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.SCANNED);
        verify(crossDeviceSessionRepository, never()).save(any());
    }

    @Test
    void submitResultSuccessConfirmsSessionWithProximityMatch() {
        CrossDeviceSession session = scannedSession("xdev1", Instant.now().plusSeconds(100));
        session.setDesktopIp("203.0.113.5");
        stubSuccessfulVerification(session);

        CrossDeviceResultResponse response = service.submitResult("xdev1", dummyResultRequest(), "203.0.113.5");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.proximity().checked()).isTrue();
        assertThat(response.proximity().mismatch()).isFalse();

        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.CONFIRMED);
        assertThat(session.getProximityMismatch()).isFalse();
        assertThat(session.getUserRefId()).isEqualTo(55L);
        assertThat(session.getCredentialPk()).isEqualTo(99L);
        assertThat(session.getIssuedJti()).isEqualTo("jti_test123");
        // DB20：完整 JWT 應持久化到 session.issuedJwt，供端點 D 以守衛式 UPDATE 領取
        // （取代原單機記憶體 pendingTokens Map）。
        assertThat(session.getIssuedJwt()).isNotBlank();

        verify(auditService).record(eq(1L), eq(55L), eq(77L), eq("XDEV_CONFIRMED"), any(), anyMap());
    }

    @Test
    void submitResultSuccessFlagsProximityMismatchButStillConfirms() {
        CrossDeviceSession session = scannedSession("xdev1", Instant.now().plusSeconds(100));
        session.setDesktopIp("203.0.113.5");
        stubSuccessfulVerification(session);

        // 手機來源 IP 與桌機發起 IP 不同 -> 只標記警示，不阻擋（S2 warn-only）。
        CrossDeviceResultResponse response = service.submitResult("xdev1", dummyResultRequest(), "198.51.100.9");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.proximity().mismatch()).isTrue();
        assertThat(session.getProximityMismatch()).isTrue();
    }

    // ------------------------------------------------------------------
    // 端點 D：pollStatus
    // ------------------------------------------------------------------

    @Test
    void pollStatusUnknownXdevIdThrowsNotFound() {
        when(crossDeviceSessionRepository.findByXdevId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pollStatus(tenant, "nope"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_NOT_FOUND);
    }

    @Test
    void pollStatusCrossTenantSessionTreatedAsNotFound() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setTenantId(2L); // 屬於另一個租戶
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.pollStatus(tenant, "xdev1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_NOT_FOUND);
    }

    @Test
    void pollStatusPendingReturnsStatusOnlyWithoutSession() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        CrossDeviceStatusResponse response = service.pollStatus(tenant, "xdev1");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.session()).isNull();
    }

    @Test
    void pollStatusLazilyExpiresStaleScannedSession() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().minusSeconds(1));
        session.setStatus(CrossDeviceSessionStatus.SCANNED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        CrossDeviceStatusResponse response = service.pollStatus(tenant, "xdev1");

        assertThat(response.status()).isEqualTo("EXPIRED");
        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.EXPIRED);
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_EXPIRED"), any(), anyMap());
    }

    @Test
    void pollStatusOnAlreadyConsumedSessionThrowsInvalidState() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.CONSUMED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.pollStatus(tenant, "xdev1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
    }

    @Test
    void pollStatusConfirmedButGuardedConsumeLosesRaceThrowsInvalidState() {
        // CONFIRMED，但資料庫層的守衛式 UPDATE（consumeConfirmedJwt）回報「這次呼叫沒搶到」
        // （模擬同一 xdevId 被另一併發輪詢，或多實例部署下的另一個 pod，搶先領走的情境；DB20）。
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.CONFIRMED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));
        when(crossDeviceSessionRepository.consumeConfirmedJwt(eq("xdev1"), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pollStatus(tenant, "xdev1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
    }

    @Test
    void pollStatusConfirmedAfterSubmitResultConsumesTokenExactlyOnce() {
        CrossDeviceSession session = scannedSession("xdev1", Instant.now().plusSeconds(100));
        session.setDesktopIp("203.0.113.5");
        stubSuccessfulVerification(session);

        service.submitResult("xdev1", dummyResultRequest(), "203.0.113.5");
        // submitResult 之後，同一個 xdevId 的 findByXdevId 應回傳「已經被 submitResult 更新過」
        // 的同一個 session 物件（模擬 repository 對同一列的後續查詢）。
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        // 模擬 CrossDeviceSessionRepository#consumeConfirmedJwt 的守衛式 UPDATE 語意：只有目前
        // 仍是 CONFIRMED 才「成功領取一次」（回傳 JWT 並清空/轉 CONSUMED），第二次呼叫（狀態已是
        // CONSUMED）回 empty。
        when(crossDeviceSessionRepository.consumeConfirmedJwt(eq("xdev1"), any(Instant.class)))
                .thenAnswer(invocation -> {
                    if (session.getStatus() != CrossDeviceSessionStatus.CONFIRMED) {
                        return Optional.empty();
                    }
                    String jwt = session.getIssuedJwt();
                    session.setStatus(CrossDeviceSessionStatus.CONSUMED);
                    session.setIssuedJwt(null);
                    return Optional.ofNullable(jwt);
                });

        CrossDeviceStatusResponse first = service.pollStatus(tenant, "xdev1");
        assertThat(first.status()).isEqualTo("CONFIRMED");
        assertThat(first.session().token()).isNotBlank();
        assertThat(first.session().tokenType()).isEqualTo("Bearer");
        assertThat(first.warnings().proximityMismatch()).isFalse();
        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.CONSUMED);
        assertThat(session.getIssuedJwt()).isNull();

        verify(auditService).record(eq(1L), eq(55L), isNull(), eq("XDEV_CONSUMED"), any(), anyMap());

        // 第二次輪詢：session 現在是 CONSUMED -> 409，且 JWT 已被清空、不能再領第二次。
        assertThatThrownBy(() -> service.pollStatus(tenant, "xdev1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
    }

    // ------------------------------------------------------------------
    // 端點 E：deny
    // ------------------------------------------------------------------

    @Test
    void denyUnknownXdevIdThrowsNotFound() {
        when(crossDeviceSessionRepository.findByXdevId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deny("nope", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_NOT_FOUND);
    }

    @Test
    void denyLazilyExpiredPendingSessionMarksExpiredAndThrows() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().minusSeconds(5));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.deny("xdev1", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_EXPIRED);

        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.EXPIRED);
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_EXPIRED"), any(), anyMap());
    }

    @Test
    void denyOnAlreadyExpiredSessionThrowsExpired() {
        // 已經被其他端點（claim/result/pollStatus）lazily 標記為 EXPIRED 的 session，deny 應
        // 一致地回 400 XDEV_SESSION_EXPIRED，而非落入「非 PENDING/SCANNED」的 409 分支。
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.EXPIRED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.deny("xdev1", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_EXPIRED);
    }

    @Test
    void denyPendingSessionTransitionsToDeniedAndWritesAudit() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        CrossDeviceDenyResponse response = service.deny("xdev1", new CrossDeviceDenyRequest("USER_CANCELLED"));

        assertThat(response.status()).isEqualTo("DENIED");
        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.DENIED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_DENIED"), any(), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsEntry("denyReason", "USER_CANCELLED");
        assertThat(detailCaptor.getValue()).containsEntry("originType", "CROSS_DEVICE_QR");
    }

    @Test
    void denyScannedSessionTransitionsToDenied() {
        CrossDeviceSession session = scannedSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        CrossDeviceDenyResponse response = service.deny("xdev1", new CrossDeviceDenyRequest("NO_CREDENTIAL"));

        assertThat(response.status()).isEqualTo("DENIED");
        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.DENIED);
    }

    @Test
    void denyWithMissingBodyNormalizesReasonToUnspecified() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        service.deny("xdev1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_DENIED"), any(), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsEntry("denyReason", "UNSPECIFIED");
    }

    @Test
    void denyWithInvalidReasonValueNormalizesToUnspecifiedInsteadOfRejectingRequest() {
        // reason 僅供稽核分類，不合法值不應讓整個放棄請求失敗（見 api-contract.md §3.4.E）。
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        CrossDeviceDenyResponse response = service.deny("xdev1", new CrossDeviceDenyRequest("not-a-real-reason"));

        assertThat(response.status()).isEqualTo("DENIED");
        assertThat(session.getStatus()).isEqualTo(CrossDeviceSessionStatus.DENIED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(1L), isNull(), isNull(), eq("XDEV_DENIED"), any(), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsEntry("denyReason", "UNSPECIFIED");
    }

    @Test
    void denyOnAlreadyDeniedSessionIsIdempotentAndDoesNotRewriteAudit() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.DENIED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        CrossDeviceDenyResponse response = service.deny("xdev1", new CrossDeviceDenyRequest("USER_CANCELLED"));

        assertThat(response.status()).isEqualTo("DENIED");
        verify(crossDeviceSessionRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), eq("XDEV_DENIED"), any(), anyMap());
    }

    @Test
    void denyOnConfirmedSessionThrowsInvalidState() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.CONFIRMED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.deny("xdev1", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
        verify(crossDeviceSessionRepository, never()).save(any());
    }

    @Test
    void denyOnConsumedSessionThrowsInvalidState() {
        CrossDeviceSession session = pendingSession("xdev1", Instant.now().plusSeconds(100));
        session.setStatus(CrossDeviceSessionStatus.CONSUMED);
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.deny("xdev1", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.XDEV_SESSION_INVALID_STATE);
        verify(crossDeviceSessionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private CrossDeviceSession pendingSession(String xdevId, Instant expiresAt) {
        CrossDeviceSession session = new CrossDeviceSession();
        session.setXdevPk(1L);
        session.setXdevId(xdevId);
        session.setTenantId(1L);
        session.setChallengePk(10L);
        session.setStatus(CrossDeviceSessionStatus.PENDING);
        session.setVerificationCode("00-000");
        session.setDesktopIp("203.0.113.5");
        session.setExpiresAt(expiresAt);
        return session;
    }

    private CrossDeviceSession scannedSession(String xdevId, Instant expiresAt) {
        CrossDeviceSession session = pendingSession(xdevId, expiresAt);
        session.setStatus(CrossDeviceSessionStatus.SCANNED);
        session.setPhoneIp("203.0.113.5");
        return session;
    }

    private CrossDeviceResultRequest dummyResultRequest() {
        return new CrossDeviceResultRequest("cred-id", "cred-id", "public-key",
                new AuthenticationResultRequest.AssertionResponse("e30", "e30", "e30", null));
    }

    private void stubSuccessfulVerification(CrossDeviceSession session) {
        when(crossDeviceSessionRepository.findByXdevId("xdev1")).thenReturn(Optional.of(session));
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        AuthChallenge challenge = new AuthChallenge();
        challenge.setChallengePk(10L);
        challenge.setCeremonyId("auth_xdev1");
        when(authChallengeRepository.findByChallengePk(10L)).thenReturn(Optional.of(challenge));

        String credentialIdB64 = B64URL.encodeToString(new byte[]{1, 2, 3, 4});
        String fakeJwt = fakeJwt("jti_test123");
        AuthenticationResultResponse result = new AuthenticationResultResponse(true, "ext-1", credentialIdB64,
                UUID.randomUUID().toString(), new AuthenticationResultResponse.SessionInfo(fakeJwt, "Bearer", 120));
        when(authenticationService.verifyResult(eq(tenant), any(AuthenticationResultRequest.class), eq(List.of("xdev"))))
                .thenReturn(result);

        FidoCredential credential = new FidoCredential();
        credential.setCredentialPk(99L);
        credential.setUserRefId(55L);
        when(fidoCredentialRepository.findByTenantIdAndCredentialIdSha256(eq(1L), any(byte[].class)))
                .thenReturn(Optional.of(credential));

        BoundDevice device = new BoundDevice();
        device.setDevicePk(77L);
        when(boundDeviceRepository.findByCredentialPk(99L)).thenReturn(Optional.of(device));
    }

    /** 組出一個「格式合法但簽章不驗證」的假 JWT：{@code extractJti} 只解析 payload 段。 */
    private static String fakeJwt(String jti) {
        String header = B64URL.encodeToString("{\"alg\":\"ES256\"}".getBytes());
        String payload = B64URL.encodeToString(("{\"jti\":\"" + jti + "\"}").getBytes());
        String signature = B64URL.encodeToString(new byte[]{0, 1, 2});
        return header + "." + payload + "." + signature;
    }
}
