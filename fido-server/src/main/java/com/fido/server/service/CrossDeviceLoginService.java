package com.fido.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.CrossDeviceSession;
import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.domain.enums.CeremonyType;
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
import com.fido.server.config.FidoProperties;
import com.fido.server.exception.ApiException;
import com.fido.server.exception.ErrorCode;
import com.fido.server.repository.AuthChallengeRepository;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.CrossDeviceSessionRepository;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 對應 api-contract.md §3.4：跨裝置 QR 登入（情境三）五個端點（A-E）的業務邏輯。
 *
 * <p><b>密碼學驗證零重寫</b>：端點 C 的 assertion 驗證完全重用既有
 * {@link AuthenticationService#verifyResult(Tenant, AuthenticationResultRequest, List)}
 * （challenge/origin/rpIdHash/UV/簽章/sign counter，含自動撤銷），本類別只在其外層加狀態機
 * 轉移與 proximity 警示檢查，並多帶 {@code "xdev"} amr 標記（D17）。
 *
 * <p><b>已簽發 session JWT 的暫存機制（實作細節，非 db-schema.md 明訂）</b>：
 * {@code cross_device_sessions} 表（db-schema.md 第 11 節）只定義 {@code issued_jti}
 * （供稽核/防重領比對），並未定義持久化整枚 JWT 字串的欄位。本類別因此把端點 C 簽發、待端點 D
 * 領取的完整 JWT 暫存於一個單機記憶體 {@link Map}（{@link #pendingTokens}），而非資料庫—— 這是
 * 刻意縮小曝險面的設計（短生命週期 bearer token 不落地資料庫，只短暫存活在記憶體，被單一
 * {@code GET .../status} 呼叫領走一次即清除）。**已知限制**：多實例水平部署時，若端點 D 的請求
 * 被負載平衡器分配到與端點 C 不同的節點，會找不到暫存的 token（見 {@link #pollStatus} 內對應
 * {@link ApiException}），比照 {@link RateLimitService} 既有「單機實作、多節點需另行處理」的
 * 前例，屬已知且非本次任務範圍的水平擴展限制。
 */
@Service
public class CrossDeviceLoginService {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ORIGIN_TYPE_CROSS_DEVICE_QR = "CROSS_DEVICE_QR";
    private static final Set<String> VALID_DENY_REASONS = Set.of("USER_CANCELLED", "NO_CREDENTIAL");
    private static final String DENY_REASON_UNSPECIFIED = "UNSPECIFIED";

    private final FidoProperties properties;
    private final ChallengeService challengeService;
    private final AuthChallengeRepository authChallengeRepository;
    private final CrossDeviceSessionRepository crossDeviceSessionRepository;
    private final TenantRepository tenantRepository;
    private final AuthenticationService authenticationService;
    private final FidoCredentialRepository fidoCredentialRepository;
    private final BoundDeviceRepository boundDeviceRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private final Map<String, JwtService.IssuedToken> pendingTokens = new ConcurrentHashMap<>();

    public CrossDeviceLoginService(FidoProperties properties,
                                    ChallengeService challengeService,
                                    AuthChallengeRepository authChallengeRepository,
                                    CrossDeviceSessionRepository crossDeviceSessionRepository,
                                    TenantRepository tenantRepository,
                                    AuthenticationService authenticationService,
                                    FidoCredentialRepository fidoCredentialRepository,
                                    BoundDeviceRepository boundDeviceRepository,
                                    AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.challengeService = challengeService;
        this.authChallengeRepository = authChallengeRepository;
        this.crossDeviceSessionRepository = crossDeviceSessionRepository;
        this.tenantRepository = tenantRepository;
        this.authenticationService = authenticationService;
        this.fidoCredentialRepository = fidoCredentialRepository;
        this.boundDeviceRepository = boundDeviceRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 端點 A：{@code POST /api/v1/authentication/cross-device/sessions}（購物網站後端）。 */
    public CrossDeviceSessionCreateResponse createSession(Tenant tenant, CrossDeviceSessionCreateRequest request) {
        int ttlSeconds = properties.getCrossDevice().getTtlSeconds();
        AuthChallenge challenge = challengeService.create(tenant, null, CeremonyType.AUTHENTICATION, ttlSeconds);

        String xdevId = generateXdevId();
        String verificationCode = generateVerificationCode();
        Instant now = Instant.now();

        CrossDeviceSession session = new CrossDeviceSession();
        session.setXdevId(xdevId);
        session.setTenantId(tenant.getTenantId());
        session.setChallengePk(challenge.getChallengePk());
        session.setStatus(CrossDeviceSessionStatus.PENDING);
        session.setVerificationCode(verificationCode);
        session.setDesktopIp(request.desktopClientIp());
        session.setExpiresAt(challenge.getExpiresAt());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        crossDeviceSessionRepository.save(session);

        auditService.record(tenant.getTenantId(), null, null, "XDEV_SESSION_CREATED", AuditOutcome.SUCCESS,
                Map.of("ceremonyId", challenge.getCeremonyId(), "originType", ORIGIN_TYPE_CROSS_DEVICE_QR));

        String qrUrl = "https://" + properties.getCrossDevice().getAppLinkHost() + "/x/" + xdevId;
        return new CrossDeviceSessionCreateResponse(xdevId, qrUrl, verificationCode, ttlSeconds);
    }

    /** 端點 B：{@code POST .../sessions/{xdevId}/claim}（手機 App 直連，xdevId capability）。 */
    public CrossDeviceClaimResponse claim(String xdevId, String phoneIp) {
        CrossDeviceSession session = requireSession(xdevId);
        expireIfNeededAndThrowIfExpired(session);
        if (session.getStatus() != CrossDeviceSessionStatus.PENDING) {
            throw new ApiException(ErrorCode.XDEV_SESSION_INVALID_STATE,
                    "Cross-device session is not in PENDING state.");
        }

        Tenant tenant = requireTenant(session.getTenantId());
        AuthChallenge challenge = requireChallenge(session.getChallengePk());

        Instant now = Instant.now();
        session.setStatus(CrossDeviceSessionStatus.SCANNED);
        session.setScannedAt(now);
        session.setPhoneIp(phoneIp);
        session.setUpdatedAt(now);
        crossDeviceSessionRepository.save(session);

        auditService.record(tenant.getTenantId(), null, null, "XDEV_CLAIMED", AuditOutcome.SUCCESS,
                Map.of("ceremonyId", challenge.getCeremonyId(), "originType", ORIGIN_TYPE_CROSS_DEVICE_QR));

        return new CrossDeviceClaimResponse(
                tenant.getRpId(),
                resolvePrimaryOrigin(tenant),
                tenant.getName(),
                B64URL.encodeToString(challenge.getChallenge()),
                session.getVerificationCode(),
                DateTimeFormatter.ISO_INSTANT.format(session.getExpiresAt()));
    }

    /** 端點 C：{@code POST .../sessions/{xdevId}/result}（手機 App 直連，xdevId capability）。 */
    public CrossDeviceResultResponse submitResult(String xdevId, CrossDeviceResultRequest request, String phoneIp) {
        CrossDeviceSession session = requireSession(xdevId);
        expireIfNeededAndThrowIfExpired(session);
        if (session.getStatus() != CrossDeviceSessionStatus.SCANNED) {
            throw new ApiException(ErrorCode.XDEV_SESSION_INVALID_STATE,
                    "Cross-device session is not in SCANNED state.");
        }

        Tenant tenant = requireTenant(session.getTenantId());
        AuthChallenge challenge = requireChallenge(session.getChallengePk());

        AuthenticationResultRequest wrapped =
                new AuthenticationResultRequest(challenge.getCeremonyId(), request.toCredentialAssertion());

        // 密碼學驗證完全重用既有邏輯；失敗時 verifyResult 自行拋出既有 ApiException
        // （422 ASSERTION_INVALID / SIGN_COUNTER_REGRESSION / CREDENTIAL_REVOKED 等）並已寫入
        // 自己的 AUTH_FAIL 稽核紀錄，這裡不吞例外、不轉譯，session 狀態維持 SCANNED（容許重試）。
        AuthenticationResultResponse result = authenticationService.verifyResult(tenant, wrapped, List.of("xdev"));

        boolean proximityMismatch = !matchesProximity(session.getDesktopIp(), phoneIp);

        byte[] credentialIdSha256 = sha256(B64URL_DEC.decode(result.credentialId()));
        FidoCredential credential = fidoCredentialRepository
                .findByTenantIdAndCredentialIdSha256(tenant.getTenantId(), credentialIdSha256)
                .orElse(null);
        Long credentialPk = credential != null ? credential.getCredentialPk() : null;
        Long userRefId = credential != null ? credential.getUserRefId() : null;
        Long devicePk = credentialPk == null ? null
                : boundDeviceRepository.findByCredentialPk(credentialPk).map(BoundDevice::getDevicePk).orElse(null);

        String token = result.session().token();
        String issuedJti = extractJti(token);

        Instant now = Instant.now();
        session.setStatus(CrossDeviceSessionStatus.CONFIRMED);
        session.setConfirmedAt(now);
        session.setPhoneIp(phoneIp);
        session.setProximityMismatch(proximityMismatch);
        session.setUserRefId(userRefId);
        session.setCredentialPk(credentialPk);
        session.setIssuedJti(issuedJti);
        session.setUpdatedAt(now);
        crossDeviceSessionRepository.save(session);

        pendingTokens.put(xdevId,
                new JwtService.IssuedToken(token, result.session().expiresIn(), issuedJti));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("originType", ORIGIN_TYPE_CROSS_DEVICE_QR);
        detail.put("proximityMismatch", proximityMismatch);
        auditService.record(tenant.getTenantId(), userRefId, devicePk, "XDEV_CONFIRMED", AuditOutcome.SUCCESS, detail);

        return new CrossDeviceResultResponse("CONFIRMED",
                new CrossDeviceResultResponse.ProximityInfo(true, proximityMismatch));
    }

    /**
     * 端點 D：{@code GET .../sessions/{xdevId}/status}（購物網站後端，X-API-Key）。
     *
     * <p>{@code tenant} 是呼叫端 X-API-Key 解析出的租戶。比照 {@link ChallengeService#requireValid}
     * 既有的「跨租戶一律視為找不到」慣例（避免任一租戶靠猜測/外洩的 {@code xdevId} 查詢到別的
     * 租戶的 session 狀態）：session 存在但 {@code tenant_id} 不屬於呼叫端租戶時，同樣回
     * {@code XDEV_SESSION_NOT_FOUND}，而非洩漏「這個 xdevId 屬於別的租戶」。
     */
    public CrossDeviceStatusResponse pollStatus(Tenant tenant, String xdevId) {
        CrossDeviceSession session = requireSession(xdevId);
        if (!session.getTenantId().equals(tenant.getTenantId())) {
            throw new ApiException(ErrorCode.XDEV_SESSION_NOT_FOUND, "Cross-device login session not found.");
        }

        if (isPreConfirmState(session.getStatus()) && session.isExpired(Instant.now())) {
            markExpired(session);
            return new CrossDeviceStatusResponse("EXPIRED", null, null);
        }

        return switch (session.getStatus()) {
            case PENDING, SCANNED, DENIED, EXPIRED -> new CrossDeviceStatusResponse(
                    session.getStatus().name(), null, null);
            case CONSUMED -> throw new ApiException(ErrorCode.XDEV_SESSION_INVALID_STATE,
                    "Cross-device session result has already been consumed.");
            case CONFIRMED -> consumeConfirmedSession(session, xdevId);
        };
    }

    private CrossDeviceStatusResponse consumeConfirmedSession(CrossDeviceSession session, String xdevId) {
        JwtService.IssuedToken issued = pendingTokens.remove(xdevId);
        if (issued == null) {
            throw new ApiException(ErrorCode.XDEV_SESSION_INVALID_STATE,
                    "Cross-device session is CONFIRMED but its issued token is no longer available on this "
                            + "server instance (known single-instance in-memory holder limitation).");
        }

        Instant now = Instant.now();
        session.setStatus(CrossDeviceSessionStatus.CONSUMED);
        session.setConsumedAt(now);
        session.setUpdatedAt(now);
        crossDeviceSessionRepository.save(session);

        auditService.record(session.getTenantId(), session.getUserRefId(), null, "XDEV_CONSUMED",
                AuditOutcome.SUCCESS, Map.of("originType", ORIGIN_TYPE_CROSS_DEVICE_QR));

        return new CrossDeviceStatusResponse("CONFIRMED",
                new CrossDeviceStatusResponse.SessionInfo(issued.token(), "Bearer", issued.expiresIn()),
                new CrossDeviceStatusResponse.Warnings(session.getProximityMismatch()));
    }

    /**
     * 端點 E：{@code POST .../sessions/{xdevId}/deny}（手機 App 直連，xdevId capability，
     * 不帶 X-API-Key）。使用者在確認畫面按「不是我，取消」或 claim 後發現本機無對應 rpId
     * 憑證時呼叫，見 api-contract.md §3.4.E。
     *
     * <p>狀態轉移：{@code PENDING}/{@code SCANNED} → {@code DENIED}；已是 {@code DENIED}
     * 冪等回 200（不重複寫稽核，§3.4.E 允許 dev 擇一，本實作選擇不重寫）；{@code CONFIRMED}/
     * {@code CONSUMED}（登入已完成）回 {@code 409 XDEV_SESSION_INVALID_STATE}；已逾時（無論是
     * 本次呼叫才發現逾時、或先前已被其他端點 lazily 標記為 {@code EXPIRED}）一律回
     * {@code 400 XDEV_SESSION_EXPIRED}。
     */
    public CrossDeviceDenyResponse deny(String xdevId, CrossDeviceDenyRequest request) {
        CrossDeviceSession session = requireSession(xdevId);
        expireIfNeededAndThrowIfExpired(session);

        String denyReason = normalizeDenyReason(request == null ? null : request.reason());

        return switch (session.getStatus()) {
            case DENIED -> new CrossDeviceDenyResponse("DENIED");
            case PENDING, SCANNED -> transitionToDenied(session, denyReason);
            case EXPIRED -> throw new ApiException(ErrorCode.XDEV_SESSION_EXPIRED,
                    "Cross-device login session has expired.");
            case CONFIRMED, CONSUMED -> throw new ApiException(ErrorCode.XDEV_SESSION_INVALID_STATE,
                    "Cross-device login has already completed and cannot be denied.");
        };
    }

    private CrossDeviceDenyResponse transitionToDenied(CrossDeviceSession session, String denyReason) {
        Instant now = Instant.now();
        session.setStatus(CrossDeviceSessionStatus.DENIED);
        session.setUpdatedAt(now);
        crossDeviceSessionRepository.save(session);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("originType", ORIGIN_TYPE_CROSS_DEVICE_QR);
        detail.put("denyReason", denyReason);
        auditService.record(session.getTenantId(), session.getUserRefId(), null, "XDEV_DENIED",
                AuditOutcome.SUCCESS, detail);

        return new CrossDeviceDenyResponse("DENIED");
    }

    /**
     * §3.4.E：{@code reason} 僅供稽核分類、不影響狀態轉移或授權判斷。省略或不在列舉值內
     * （非 {@code USER_CANCELLED}/{@code NO_CREDENTIAL}）一律正規化為 {@code UNSPECIFIED}，
     * 刻意不因此拒絕整個請求——這欄位對安全判斷零影響，讓格式不符的值擋下真正要做的狀態轉移
     * （使用者取消訊號）不划算，比照 spec 用語「伺服器不信任此值作任何授權判斷」的精神放寬處理。
     */
    private static String normalizeDenyReason(String rawReason) {
        if (rawReason == null || !VALID_DENY_REASONS.contains(rawReason)) {
            return DENY_REASON_UNSPECIFIED;
        }
        return rawReason;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean isPreConfirmState(CrossDeviceSessionStatus status) {
        return status == CrossDeviceSessionStatus.PENDING || status == CrossDeviceSessionStatus.SCANNED;
    }

    private CrossDeviceSession requireSession(String xdevId) {
        return crossDeviceSessionRepository.findByXdevId(xdevId)
                .orElseThrow(() -> new ApiException(ErrorCode.XDEV_SESSION_NOT_FOUND,
                        "Cross-device login session not found."));
    }

    /** claim/result 專用：若處於 PENDING/SCANNED 且已逾時，落 EXPIRED 並丟 400。 */
    private void expireIfNeededAndThrowIfExpired(CrossDeviceSession session) {
        if (isPreConfirmState(session.getStatus()) && session.isExpired(Instant.now())) {
            markExpired(session);
            throw new ApiException(ErrorCode.XDEV_SESSION_EXPIRED, "Cross-device login session has expired.");
        }
    }

    private void markExpired(CrossDeviceSession session) {
        session.setStatus(CrossDeviceSessionStatus.EXPIRED);
        session.setUpdatedAt(Instant.now());
        crossDeviceSessionRepository.save(session);
        auditService.record(session.getTenantId(), session.getUserRefId(), null, "XDEV_EXPIRED", AuditOutcome.FAILURE,
                Map.of("originType", ORIGIN_TYPE_CROSS_DEVICE_QR));
    }

    private Tenant requireTenant(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "cross_device_sessions.tenant_id=" + tenantId + " references a tenant that no longer exists."));
    }

    private AuthChallenge requireChallenge(Long challengePk) {
        return authChallengeRepository.findByChallengePk(challengePk)
                .orElseThrow(() -> new IllegalStateException(
                        "cross_device_sessions.challenge_pk=" + challengePk + " references a missing auth_challenges row."));
    }

    /** 只影響警示欄位（S2 warn-only），無法比對（任一方 IP 缺失）一律視為不吻合。 */
    private static boolean matchesProximity(String desktopIp, String phoneIp) {
        if (desktopIp == null || phoneIp == null) {
            return false;
        }
        return desktopIp.trim().equalsIgnoreCase(phoneIp.trim());
    }

    private String extractJti(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] payload = B64URL_DEC.decode(parts[1]);
            JsonNode node = objectMapper.readTree(payload);
            JsonNode jti = node.get("jti");
            return jti == null ? null : jti.asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generateXdevId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64URL.encodeToString(bytes);
    }

    private String generateVerificationCode() {
        int part1 = RANDOM.nextInt(100);
        int part2 = RANDOM.nextInt(1000);
        return String.format("%02d-%03d", part1, part2);
    }

    /**
     * {@code tenants.expected_origin} 可存單一字串或 JSON 陣列字串（db-schema.md）；跨裝置情境
     * 需要伺服器直接回傳「要簽的那一個」origin 字串給 App（設計文件 5.1 節），故取陣列第一個
     * 元素；非 JSON 陣列格式則視為單一 origin 字串直接回傳。
     */
    private String resolvePrimaryOrigin(Tenant tenant) {
        String raw = tenant.getExpectedOrigin();
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            List<?> list = objectMapper.readValue(raw, List.class);
            if (!list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        } catch (Exception ignored) {
            // 非 JSON 陣列格式，視為單一 origin 字串。
        }
        return raw;
    }
}
