package com.fido.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fido.server.domain.AuditLog;
import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.FidoUserRef;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.domain.enums.CeremonyType;
import com.fido.server.domain.enums.ChallengeStatus;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;
import com.fido.server.domain.enums.SecurityLevel;
import com.fido.server.domain.enums.TenantStatus;
import com.fido.server.repository.AuditLogRepository;
import com.fido.server.repository.AuthChallengeRepository;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.FidoUserRefRepository;
import com.fido.server.repository.TenantRepository;
import com.fido.server.repository.jpa.JpaAuditLogRepository;
import com.fido.server.repository.jpa.JpaAuthChallengeRepository;
import com.fido.server.repository.jpa.JpaBoundDeviceRepository;
import com.fido.server.repository.jpa.JpaFidoCredentialRepository;
import com.fido.server.repository.jpa.JpaFidoUserRefRepository;
import com.fido.server.repository.jpa.JpaTenantRepository;
import com.fido.server.testsupport.TestKeyAttestationFixtures;
import com.fido.server.testsupport.WebAuthnCeremonyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 證明 {@code fido.persistence.mode=jpa} 搭配 H2（MSSQL 相容模式，見
 * {@code application-h2.yml} / {@code db/h2/schema-h2.sql}）時，JPA entity mapping 與
 * {@code com.fido.server.repository.jpa.*} 的查詢邏輯是真的可以動的，不是只有介面接上、底層
 * 邏輯完全沒被跑過。
 *
 * <p>分兩部分：
 * <ol>
 *   <li>{@link #fullRegistrationAndAuthenticationFlowPersistsThroughJpaAndH2()}：與
 *       {@link RegistrationAndAuthenticationFlowTest} 相同的端對端 HTTP 流程（註冊 -&gt; 登入
 *       -&gt; sign counter 倒退自動撤銷），但這裡额外直接繞過 HTTP 層、用注入的
 *       {@code Jpa*Repository} bean 讀資料庫，確認寫入的資料真的在（H2）資料庫裡，且欄位
 *       內容正確 —— 只驗證 HTTP 回應不足以證明 JPA mapping 正確（回應可能剛好巧合對，但底層
 *       其實沒寫進資料庫或寫錯欄位）。</li>
 *   <li>{@link #repositoryRoundTripCoversAllSixTables()}：直接呼叫六個 repository 介面
 *       （此時注入的是 JPA 實作）逐一做 save + 查詢，涵蓋所有介面方法簽章（含 byte[]/UUID/enum/
 *       Instant 欄位的 round-trip），確保 HTTP 流程沒覆蓋到的查詢方法（如
 *       {@code findByRpId}、{@code findByTenantIdAndUserRefId} 稽核查詢）也真的被跑過一次。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Import(RegistrationAndAuthenticationFlowTest.TestAttestationRootConfig.class)
class JpaPersistenceH2FlowTest {

    private static final String API_KEY = "dev-api-key-00000000000000000000";
    private static final String RP_ID = "shop.example.com";
    private static final String ORIGIN = "https://shop.example.com";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaTenantRepository jpaTenantRepository;

    @Autowired
    private JpaFidoUserRefRepository jpaFidoUserRefRepository;

    @Autowired
    private JpaFidoCredentialRepository jpaFidoCredentialRepository;

    @Autowired
    private JpaBoundDeviceRepository jpaBoundDeviceRepository;

    @Autowired
    private JpaAuthChallengeRepository jpaAuthChallengeRepository;

    @Autowired
    private JpaAuditLogRepository jpaAuditLogRepository;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    @Test
    void fullRegistrationAndAuthenticationFlowPersistsThroughJpaAndH2() throws Exception {
        String externalUserId = "u-h2-" + WebAuthnCeremonyFixtures.RANDOM.nextInt(1_000_000);

        // dev-seed 租戶（DevDataSeeder）在 mode=jpa 時也是透過 JpaTenantRepository 寫入 H2，
        // 這裡先確認能透過真實 SQL 查回來（而非只存在於某個記憶體 Map 裡）。
        Optional<Tenant> seededTenant = jpaTenantRepository.findByRpId(RP_ID);
        assertThat(seededTenant).isPresent();
        assertThat(seededTenant.get().getTenantId()).isNotNull();

        // ---- 1. 註冊：取得 options ----
        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId,
                                "displayName", "H2 Test User",
                                "deviceLabel", "My H2 Test Device"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ceremonyId").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();

        // ceremony 直接可以從 H2 用 JpaAuthChallengeRepository 查回，證明 auth_challenges 表寫入正確。
        Optional<AuthChallenge> persistedChallenge = jpaAuthChallengeRepository.findByCeremonyId(ceremonyId);
        assertThat(persistedChallenge).isPresent();
        assertThat(persistedChallenge.get().getCeremonyType()).isEqualTo(CeremonyType.REGISTRATION);
        assertThat(persistedChallenge.get().getStatus()).isEqualTo(ChallengeStatus.PENDING);
        assertThat(persistedChallenge.get().getChallenge())
                .isEqualTo(B64URL_DEC.decode(challengeB64));

        // ---- 2. 註冊：組出「密碼學上合法」的 credential，送出 result ----
        KeyPair credentialKeyPair = WebAuthnCeremonyFixtures.generateEcKeyPair();
        byte[] credentialId = WebAuthnCeremonyFixtures.randomBytes(32);
        byte[] coseKeyBytes = WebAuthnCeremonyFixtures.buildEcCoseKeyBytes(cborMapper, (ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(RP_ID, (byte) 0x41, 0L,
                new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = WebAuthnCeremonyFixtures.sha256(clientDataJson);

        byte[] challengeBytes = B64URL_DEC.decode(challengeB64);
        X509Certificate leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                credentialKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX, challengeBytes);
        byte[] attStmtSig = WebAuthnCeremonyFixtures.signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);
        byte[] attestationObject = WebAuthnCeremonyFixtures.buildAttestationObject(
                cborMapper, "android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", java.util.List.of("internal")));

        String resultResp = mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential,
                                "deviceLabel", "My H2 Test Device"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.securityLevel").value("STRONG_BOX"))
                .andExpect(jsonPath("$.signCount").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode regResultJson = jsonMapper.readTree(resultResp);
        UUID deviceId = UUID.fromString(regResultJson.get("deviceId").asText());

        // 直接繞過 HTTP，用 JPA repository 從 H2 讀出剛剛寫入的憑證/裝置，逐欄位核對。
        Optional<FidoUserRef> persistedUserRef = jpaFidoUserRefRepository.findByTenantIdAndExternalUserId(
                seededTenant.get().getTenantId(), externalUserId);
        assertThat(persistedUserRef).isPresent();

        Optional<BoundDevice> persistedDevice = jpaBoundDeviceRepository.findByDeviceId(deviceId);
        assertThat(persistedDevice).isPresent();
        assertThat(persistedDevice.get().getSecurityLevel()).isEqualTo(SecurityLevel.STRONG_BOX);
        assertThat(persistedDevice.get().getStatus()).isEqualTo(RecordStatus.ACTIVE);
        assertThat(persistedDevice.get().getDeviceName()).isEqualTo("My H2 Test Device");

        Optional<FidoCredential> persistedCredential = jpaFidoCredentialRepository.findByCredentialPk(
                persistedDevice.get().getCredentialPk());
        assertThat(persistedCredential).isPresent();
        assertThat(persistedCredential.get().getCredentialId()).isEqualTo(credentialId);
        assertThat(persistedCredential.get().getSignCount()).isEqualTo(0L);
        assertThat(persistedCredential.get().getStatus()).isEqualTo(RecordStatus.ACTIVE);

        // countByUserRefIdAndStatus / findByUserRefIdAndStatus：真的對 H2 執行聚合與條件查詢。
        assertThat(jpaFidoCredentialRepository.countByUserRefIdAndStatus(
                persistedUserRef.get().getUserRefId(), RecordStatus.ACTIVE)).isEqualTo(1L);

        // ---- 3. 登入：正常路徑（sign counter 0 -> 1） ----
        String authOptionsResp = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode authOptionsJson = jsonMapper.readTree(authOptionsResp);
        String authCeremonyId = authOptionsJson.get("ceremonyId").asText();
        String authChallengeB64 = authOptionsJson.get("publicKey").get("challenge").asText();

        submitAssertion(credentialId, credentialKeyPair.getPrivate(), authCeremonyId, authChallengeB64, 1L,
                status().isOk());

        // sign_count 更新是否真的寫回 H2（而非只在記憶體物件上打了 setter 卻沒 flush）。
        Optional<FidoCredential> afterLogin = jpaFidoCredentialRepository.findByCredentialPk(
                persistedCredential.get().getCredentialPk());
        assertThat(afterLogin).isPresent();
        assertThat(afterLogin.get().getSignCount()).isEqualTo(1L);
        assertThat(afterLogin.get().getLastUsedAt()).isNotNull();

        // ---- 4. Sign counter 倒退 -> 422，且憑證/裝置在 H2 裡被自動撤銷 ----
        String authOptionsResp2 = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andReturn().getResponse().getContentAsString();
        JsonNode authOptionsJson2 = jsonMapper.readTree(authOptionsResp2);
        String authCeremonyId2 = authOptionsJson2.get("ceremonyId").asText();
        String authChallengeB64_2 = authOptionsJson2.get("publicKey").get("challenge").asText();

        submitAssertion(credentialId, credentialKeyPair.getPrivate(), authCeremonyId2, authChallengeB64_2, 0L,
                status().isUnprocessableEntity());

        Optional<FidoCredential> afterRegression = jpaFidoCredentialRepository.findByCredentialPk(
                persistedCredential.get().getCredentialPk());
        assertThat(afterRegression).isPresent();
        assertThat(afterRegression.get().getStatus()).isEqualTo(RecordStatus.REVOKED);
        assertThat(afterRegression.get().getRevokedReason()).isEqualTo(RevokedReason.COUNTER_REGRESSION);

        Optional<BoundDevice> deviceAfterRegression = jpaBoundDeviceRepository.findByDeviceId(deviceId);
        assertThat(deviceAfterRegression).isPresent();
        assertThat(deviceAfterRegression.get().getStatus()).isEqualTo(RecordStatus.REVOKED);
        assertThat(deviceAfterRegression.get().getRevokedReason()).isEqualTo(RevokedReason.COUNTER_REGRESSION);

        // fido-status 端點（讀路徑）也應反映 H2 裡的最新狀態。
        mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));

        // AUTO_REVOKE 稽核事件應已寫入 audit_log（H2），可用 findByTenantIdAndUserRefId 查回。
        var auditEvents = jpaAuditLogRepository.findByTenantIdAndUserRefId(
                seededTenant.get().getTenantId(), persistedUserRef.get().getUserRefId(), 50);
        assertThat(auditEvents).isNotEmpty();
        assertThat(auditEvents).anySatisfy(e -> assertThat(e.getEventType()).contains("COUNTER_REGRESSION"));
    }

    /**
     * 直接對六個 repository 介面（此時注入的是 JPA 實作）逐一 save + 查詢，涵蓋 HTTP 端對端流程
     * 沒有覆蓋到的方法（{@code findByRpId}、{@code findByApiKeyHash}、
     * {@code findByUserRefIdAndStatus}、{@code findByUserRefId}、稽核查詢的 tenantId/userRefId
     * 皆為 null 的 pre-auth 事件等），確認每個查詢方法都真的對 H2 送出並拿回正確結果，且
     * byte[]/UUID/enum/Instant 欄位 round-trip 後與寫入前一致。
     */
    @Test
    void repositoryRoundTripCoversAllSixTables() throws Exception {
        TenantRepository tenantRepository = jpaTenantRepository;
        FidoUserRefRepository userRefRepository = jpaFidoUserRefRepository;
        FidoCredentialRepository credentialRepository = jpaFidoCredentialRepository;
        BoundDeviceRepository deviceRepository = jpaBoundDeviceRepository;
        AuthChallengeRepository challengeRepository = jpaAuthChallengeRepository;
        AuditLogRepository auditLogRepository = jpaAuditLogRepository;

        // 1) tenants
        Tenant tenant = new Tenant();
        tenant.setName("Round Trip Shop");
        tenant.setRpId("roundtrip-" + UUID.randomUUID() + ".example.com");
        tenant.setExpectedOrigin("[\"https://roundtrip.example.com\"]");
        byte[] apiKeyHash = WebAuthnCeremonyFixtures.randomBytes(32);
        tenant.setApiKeyHash(apiKeyHash);
        tenant.setApiKeyPrefix("rt_prefix1");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setRateLimitTps(42);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        Tenant savedTenant = tenantRepository.save(tenant);
        assertThat(savedTenant.getTenantId()).isNotNull();

        assertThat(tenantRepository.findByApiKeyHash(apiKeyHash))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.getRpId()).isEqualTo(tenant.getRpId()));
        assertThat(tenantRepository.findByTenantUid(savedTenant.getTenantUid())).isPresent();
        assertThat(tenantRepository.findByRpId(tenant.getRpId())).isPresent();
        assertThat(tenantRepository.findById(savedTenant.getTenantId())).isPresent();

        // 2) fido_user_ref
        FidoUserRef userRef = new FidoUserRef();
        userRef.setTenantId(savedTenant.getTenantId());
        userRef.setExternalUserId("round-trip-user-1");
        byte[] userHandle = WebAuthnCeremonyFixtures.randomBytes(32);
        userRef.setUserHandle(userHandle);
        userRef.setDisplayName("Round Trip User");
        userRef.setCreatedAt(now);
        userRef.setUpdatedAt(now);
        FidoUserRef savedUserRef = userRefRepository.save(userRef);
        assertThat(savedUserRef.getUserRefId()).isNotNull();

        Optional<FidoUserRef> foundUserRef = userRefRepository.findByTenantIdAndExternalUserId(
                savedTenant.getTenantId(), "round-trip-user-1");
        assertThat(foundUserRef).isPresent();
        assertThat(foundUserRef.get().getUserHandle()).isEqualTo(userHandle);
        assertThat(userRefRepository.findById(savedUserRef.getUserRefId())).isPresent();

        // 3) fido_credentials
        FidoCredential credential = new FidoCredential();
        credential.setUserRefId(savedUserRef.getUserRefId());
        credential.setTenantId(savedTenant.getTenantId());
        byte[] credentialIdBytes = WebAuthnCeremonyFixtures.randomBytes(64);
        byte[] credentialIdSha256 = WebAuthnCeremonyFixtures.randomBytes(32);
        credential.setCredentialId(credentialIdBytes);
        credential.setCredentialIdSha256(credentialIdSha256);
        credential.setPublicKey(WebAuthnCeremonyFixtures.randomBytes(77));
        credential.setCoseAlg(-7);
        credential.setSignCount(0L);
        byte[] aaguid = WebAuthnCeremonyFixtures.randomBytes(16);
        credential.setAaguid(aaguid);
        credential.setTransports("[\"internal\"]");
        credential.setAttestationFormat("android-key");
        credential.setStatus(RecordStatus.ACTIVE);
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);
        FidoCredential savedCredential = credentialRepository.save(credential);
        assertThat(savedCredential.getCredentialPk()).isNotNull();

        Optional<FidoCredential> foundBySha = credentialRepository.findByTenantIdAndCredentialIdSha256(
                savedTenant.getTenantId(), credentialIdSha256);
        assertThat(foundBySha).isPresent();
        assertThat(foundBySha.get().getAaguid()).isEqualTo(aaguid);
        assertThat(credentialRepository.findByUserRefId(savedUserRef.getUserRefId())).hasSize(1);
        assertThat(credentialRepository.findByUserRefIdAndStatus(savedUserRef.getUserRefId(), RecordStatus.ACTIVE))
                .hasSize(1);
        assertThat(credentialRepository.countByUserRefIdAndStatus(savedUserRef.getUserRefId(), RecordStatus.REVOKED))
                .isEqualTo(0L);

        // 4) bound_devices
        BoundDevice device = new BoundDevice();
        device.setCredentialPk(savedCredential.getCredentialPk());
        device.setUserRefId(savedUserRef.getUserRefId());
        device.setTenantId(savedTenant.getTenantId());
        device.setDeviceName("Round Trip Device");
        device.setModel("Pixel 9");
        device.setOsVersion("Android 14");
        device.setSecurityLevel(SecurityLevel.TEE);
        device.setAttestationSummary("{\"root\":\"test\"}");
        device.setStatus(RecordStatus.ACTIVE);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        BoundDevice savedDevice = deviceRepository.save(device);
        assertThat(savedDevice.getDevicePk()).isNotNull();

        assertThat(deviceRepository.findByDeviceId(savedDevice.getDeviceId())).isPresent();
        assertThat(deviceRepository.findByCredentialPk(savedCredential.getCredentialPk())).isPresent();
        assertThat(deviceRepository.findByUserRefId(savedUserRef.getUserRefId())).hasSize(1);
        assertThat(deviceRepository.findByUserRefIdAndStatus(savedUserRef.getUserRefId(), RecordStatus.ACTIVE))
                .hasSize(1);

        // 5) auth_challenges（usernameless 登入情境：user_ref_id 可為 NULL）
        AuthChallenge challenge = new AuthChallenge();
        challenge.setCeremonyId("auth_roundtrip_" + UUID.randomUUID());
        challenge.setTenantId(savedTenant.getTenantId());
        challenge.setUserRefId(null);
        challenge.setChallenge(WebAuthnCeremonyFixtures.randomBytes(32));
        challenge.setCeremonyType(CeremonyType.AUTHENTICATION);
        challenge.setStatus(ChallengeStatus.PENDING);
        challenge.setExpiresAt(now.plusSeconds(60));
        challenge.setCreatedAt(now);
        AuthChallenge savedChallenge = challengeRepository.save(challenge);
        assertThat(savedChallenge.getChallengePk()).isNotNull();

        Optional<AuthChallenge> foundChallenge = challengeRepository.findByCeremonyId(challenge.getCeremonyId());
        assertThat(foundChallenge).isPresent();
        assertThat(foundChallenge.get().getUserRefId()).isNull();
        assertThat(foundChallenge.get().getChallenge()).isEqualTo(challenge.getChallenge());

        // 6) audit_log（pre-auth 事件：tenantId/userRefId 皆可為 NULL）
        AuditLog preAuthEvent = new AuditLog();
        preAuthEvent.setTenantId(null);
        preAuthEvent.setUserRefId(null);
        preAuthEvent.setEventType("API_KEY_INVALID");
        preAuthEvent.setOutcome(AuditOutcome.FAILURE);
        preAuthEvent.setRequestId("req-roundtrip-1");
        preAuthEvent.setIpAddress("127.0.0.1");
        preAuthEvent.setDetail("{\"reason\":\"unit-test\"}");
        preAuthEvent.setCreatedAt(now);
        AuditLog savedPreAuthEvent = auditLogRepository.save(preAuthEvent);
        assertThat(savedPreAuthEvent.getAuditId()).isNotNull();

        AuditLog tenantScopedEvent = new AuditLog();
        tenantScopedEvent.setTenantId(savedTenant.getTenantId());
        tenantScopedEvent.setUserRefId(savedUserRef.getUserRefId());
        tenantScopedEvent.setEventType("REG_SUCCESS");
        tenantScopedEvent.setOutcome(AuditOutcome.SUCCESS);
        tenantScopedEvent.setCreatedAt(now);
        auditLogRepository.save(tenantScopedEvent);

        var eventsForUser = auditLogRepository.findByTenantIdAndUserRefId(
                savedTenant.getTenantId(), savedUserRef.getUserRefId(), 10);
        assertThat(eventsForUser).hasSize(1);
        assertThat(eventsForUser.get(0).getEventType()).isEqualTo("REG_SUCCESS");
    }

    private void submitAssertion(byte[] credentialId, PrivateKey credentialPrivateKey, String ceremonyId,
                                  String challengeB64, long signCount, org.springframework.test.web.servlet.ResultMatcher expectedStatus)
            throws Exception {
        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(RP_ID, (byte) 0x05, signCount,
                null, null, null);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.get", challengeB64, ORIGIN);
        byte[] signature = WebAuthnCeremonyFixtures.signEcdsa(credentialPrivateKey, authenticatorData,
                WebAuthnCeremonyFixtures.sha256(clientDataJson));

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        mockMvc.perform(post("/api/v1/authentication/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("ceremonyId", ceremonyId, "credential", credential))))
                .andExpect(expectedStatus);
    }
}
