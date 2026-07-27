package com.fido.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.TenantStatus;
import com.fido.server.repository.AuthChallengeRepository;
import com.fido.server.repository.TenantRepository;
import com.fido.server.service.ApiKeyService;
import com.fido.server.service.MetricNames;
import com.fido.server.testsupport.TestKeyAttestationFixtures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 補齊 {@link RegistrationAndAuthenticationFlowTest} 未涵蓋的情境：
 * <ul>
 *   <li>裝置管理 API（{@code DeviceController}/{@code DeviceService}）</li>
 *   <li>Sign counter 正常遞增與異常自動撤銷（{@code AuthenticationService}）</li>
 *   <li>租戶速率限制 429（{@code RateLimitService}/{@code ApiKeyAuthFilter}）</li>
 *   <li>Challenge 60 秒過期路徑（{@code ChallengeService}，與「查無 ceremonyId」區分）</li>
 *   <li>密碼學邊界情況（leaf 公鑰與 credential 公鑰不一致、attStmt.sig 竄改、
 *       x5c 鏈尾非受信任 root、assertion 簽章竄改）</li>
 * </ul>
 *
 * <p>沿用 {@link RegistrationAndAuthenticationFlowTest.TestAttestationRootConfig}，把
 * {@code TrustedRootCertificateStore} 換成僅信任 {@link TestKeyAttestationFixtures#ROOT_CERTIFICATE}
 * 的版本，與主測試檔共用同一組信任錨點設定。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationAndAuthenticationFlowTest.TestAttestationRootConfig.class)
class DeviceManagementAndSecurityEdgeCaseTest {

    private static final String API_KEY = "dev-api-key-00000000000000000000";
    private static final String RP_ID = "shop.example.com";
    private static final String ORIGIN = "https://shop.example.com";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ATTESTATION_EXTENSION_OID = "1.3.6.1.4.1.11129.2.1.17";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthChallengeRepository authChallengeRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private MeterRegistry meterRegistry;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    // ------------------------------------------------------------------
    // 1. 裝置管理 API
    // ------------------------------------------------------------------

    @Test
    void listDevicesReturnsRegisteredDeviceForActiveUser() throws Exception {
        String externalUserId = "u-list-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "My Device");

        mockMvc.perform(get("/api/v1/users/{id}/devices", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalUserId").value(externalUserId))
                .andExpect(jsonPath("$.devices.length()").value(1))
                .andExpect(jsonPath("$.devices[0].deviceId").value(device.deviceId()))
                .andExpect(jsonPath("$.devices[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.devices[0].credentialId").value(B64URL.encodeToString(device.credentialId())));
    }

    @Test
    void listDevicesForUnknownUserReturns200EmptyArrayNotFoundIsNotUsed() throws Exception {
        // 防列舉：使用者根本不存在（未曾建立 fido_user_ref）時，依 api-contract.md 4.1 D7
        // 仍應回 200 + 空陣列，不是 404。
        mockMvc.perform(get("/api/v1/users/{id}/devices", "no-such-user-" + RANDOM.nextInt(1_000_000))
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices").isArray())
                .andExpect(jsonPath("$.devices").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void revokeDeviceMarksRevokedAndCredentialBecomesUnusableForLogin() throws Exception {
        String externalUserId = "u-revoke-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "Device To Revoke");

        // 撤銷前：先成功登入一次，確認 credential 目前是可用的（排除本來就壞掉的 baseline）。
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 1L)
                .andExpect(status().isOk());

        // 撤銷裝置。
        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", externalUserId, device.deviceId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(device.deviceId()))
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 裝置列表（ALL）應顯示 REVOKED。
        mockMvc.perform(get("/api/v1/users/{id}/devices", externalUserId)
                        .header("X-API-Key", API_KEY).param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].status").value("REVOKED"));

        // 撤銷後：用同一把私鑰、遞增的 sign counter 再嘗試登入，應失敗（credential 已連動失效）。
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 2L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CREDENTIAL_REVOKED"));
    }

    @Test
    void revokeDeviceWithUnknownDeviceIdIsIdempotentNoOp() throws Exception {
        String externalUserId = "u-revoke-unknown-" + RANDOM.nextInt(1_000_000);
        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", externalUserId, UUID.randomUUID())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void revokeDeviceNotOwnedByUserIsIdempotentNoOpAndDoesNotAffectActualOwner() throws Exception {
        String ownerUserId = "u-owner-" + RANDOM.nextInt(1_000_000);
        String attackerUserId = "u-attacker-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice ownerDevice = registerDevice(ownerUserId, "Owner Device");
        // attackerUserId 也要是「已存在」的使用者（有自己的 fido_user_ref），確保驗證的是
        // 「裝置不屬於此使用者」的分支，而非「使用者不存在」分支。
        registerDevice(attackerUserId, "Attacker Own Device");

        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", attackerUserId, ownerDevice.deviceId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED")); // 冪等回應，呼叫端無法分辨真假

        // 但實際上 owner 的裝置必須仍是 ACTIVE，沒有被真的撤銷。
        mockMvc.perform(get("/api/v1/users/{id}/devices", ownerUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].deviceId").value(ownerDevice.deviceId()))
                .andExpect(jsonPath("$.devices[0].status").value("ACTIVE"));
    }

    @Test
    void revokeDeviceThatIsAlreadyRevokedIsIdempotentNoOp() throws Exception {
        String externalUserId = "u-double-revoke-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "Double Revoke Device");

        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", externalUserId, device.deviceId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 再撤銷一次：依然 200 REVOKED（冪等 no-op），不是錯誤。
        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", externalUserId, device.deviceId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    // ------------------------------------------------------------------
    // 2. Sign counter 正常遞增 / 異常自動撤銷
    // ------------------------------------------------------------------

    @Test
    void signCounterIncrementsNormallyAcrossSuccessiveLogins() throws Exception {
        String externalUserId = "u-counter-ok-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "Counter OK Device");

        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 1L)
                .andExpect(status().isOk());
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 5L)
                .andExpect(status().isOk());
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 6L)
                .andExpect(status().isOk());

        // 仍應是啟用狀態（未被誤判撤銷）。
        mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true));
    }

    @Test
    void signCounterRegressionAutoRevokesAndSubsequentValidCounterLoginStillFailsAsRevoked() throws Exception {
        String externalUserId = "u-counter-regress-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "Counter Regress Device");

        // 基準值：counter 是共用 registry、不帶 tag，跨測試方法/測試類別可能已被其他情境增加過，
        // 故用「執行前後差值」而非絕對值斷言（fido.auth.verify.failures 因帶 reason tag，改用
        // find(name).tags(reason,...) 精準鎖定該 tag 組合，同樣採差值）。
        double autoRevocationsBefore = counterCount(MetricNames.CREDENTIAL_AUTO_REVOCATIONS);
        double counterRegressionFailuresBefore = authVerifyFailuresCount("SIGN_COUNTER_REGRESSION");
        double credentialRevokedFailuresBefore = authVerifyFailuresCount("CREDENTIAL_REVOKED");

        // 正常登入一次，把 sign_count 推到 5。
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 5L)
                .andExpect(status().isOk());

        // 倒退：counter=2 < stored=5 -> 應被拒絕且自動撤銷。
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 2L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SIGN_COUNTER_REGRESSION"));

        // fido.credential.auto_revocations（不帶 tag）與 fido.auth.verify.failures{reason=
        // SIGN_COUNTER_REGRESSION} 皆應 +1（CLAUDE.md「內部可觀測性」交辦第 2 點：兩個指標可
        // 對同一事件都增加，語意不同——前者是「自動撤銷發生次數」，後者是「驗證失敗次數」）。
        assertThat(counterCount(MetricNames.CREDENTIAL_AUTO_REVOCATIONS)).isEqualTo(autoRevocationsBefore + 1);
        assertThat(authVerifyFailuresCount("SIGN_COUNTER_REGRESSION"))
                .isEqualTo(counterRegressionFailuresBefore + 1);

        // 撤銷是否「真的生效」：裝置狀態應變 REVOKED。
        mockMvc.perform(get("/api/v1/users/{id}/devices", externalUserId)
                        .header("X-API-Key", API_KEY).param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].deviceId").value(device.deviceId()))
                .andExpect(jsonPath("$.devices[0].status").value("REVOKED"));

        // 之後即使用「正確遞增」的 sign counter（6 > 5）重試，仍應失敗，因為 credential 已被撤銷
        // （驗證撤銷不是只回錯誤但狀態沒變，而是真的持續阻擋後續登入）。
        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 6L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CREDENTIAL_REVOKED"));

        // fido.auth.verify.failures{reason=CREDENTIAL_REVOKED} 也應 +1，但 auto_revocations
        // 不應再變動（這次是「用已撤銷憑證登入」，不是新一次的自動撤銷事件）。
        assertThat(authVerifyFailuresCount("CREDENTIAL_REVOKED"))
                .isEqualTo(credentialRevokedFailuresBefore + 1);
        assertThat(counterCount(MetricNames.CREDENTIAL_AUTO_REVOCATIONS)).isEqualTo(autoRevocationsBefore + 1);
    }

    // ------------------------------------------------------------------
    // 3. 租戶速率限制
    // ------------------------------------------------------------------

    @Test
    void exceedingTenantRateLimitReturns429WithRetryAfter() throws Exception {
        // 建一個獨立租戶、獨立 API Key，設定極低 TPS，避免影響其他測試共用的 dev-seed 租戶。
        String rawApiKey = "test-ratelimit-key-" + UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setName("Rate Limit Test Tenant");
        tenant.setRpId(RP_ID);
        tenant.setExpectedOrigin("[\"" + ORIGIN + "\"]");
        tenant.setApiKeyHash(apiKeyService.hash(rawApiKey));
        tenant.setApiKeyPrefix(apiKeyService.prefix(rawApiKey));
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setRateLimitTps(3);
        tenantRepository.save(tenant);

        String externalUserId = "u-ratelimit-" + RANDOM.nextInt(1_000_000);

        int total = 10;
        int okCount = 0;
        int rateLimitedCount = 0;
        MvcResult firstRateLimited = null;
        for (int i = 0; i < total; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId)
                            .header("X-API-Key", rawApiKey))
                    .andReturn();
            int sc = result.getResponse().getStatus();
            if (sc == 200) {
                okCount++;
            } else if (sc == 429) {
                rateLimitedCount++;
                if (firstRateLimited == null) {
                    firstRateLimited = result;
                }
            }
        }

        assertTrue(okCount > 0, "在超過限制之前應該至少有請求成功（實際 okCount=" + okCount + "）");
        assertTrue(rateLimitedCount > 0, "設定 TPS=3 卻連打 10 次，應該至少觸發一次 429（實際 rateLimitedCount="
                + rateLimitedCount + "，okCount=" + okCount + "）");

        String retryAfter = firstRateLimited.getResponse().getHeader("Retry-After");
        assertTrue(retryAfter != null && !retryAfter.isBlank(), "429 回應應帶 Retry-After header");
        JsonNode body = jsonMapper.readTree(firstRateLimited.getResponse().getContentAsString());
        assertTrue("RATE_LIMITED".equals(body.get("error").get("code").asText()),
                "429 回應的 error.code 應為 RATE_LIMITED，實際為 " + body.get("error").get("code"));

        // fido.ratelimit.rejections：唯一允許帶 tenant tag 的 counter（CLAUDE.md「內部可觀測性」
        // 交辦第 2 點標籤硬規則），值為 tenant_uid。此租戶是本測試方法內新建、tag 值唯一，
        // 不需要差值手法，直接比對 count == rateLimitedCount 即可。
        Counter rejectionsCounter = meterRegistry.find(MetricNames.RATE_LIMIT_REJECTIONS)
                .tag(MetricNames.TAG_TENANT, tenant.getTenantUid().toString())
                .counter();
        assertThat(rejectionsCounter).isNotNull();
        assertThat(rejectionsCounter.count()).isEqualTo((double) rateLimitedCount);
    }

    // ------------------------------------------------------------------
    // 4. Challenge 60 秒過期（與「查無 ceremonyId」區分）
    // ------------------------------------------------------------------

    @Test
    void registrationResultWithExpiredChallengeReturnsChallengeExpiredNotChallengeNotFound() throws Exception {
        String externalUserId = "u-expired-reg-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Expired Test"))))
                .andReturn().getResponse().getContentAsString();
        String ceremonyId = jsonMapper.readTree(optionsResp).get("ceremonyId").asText();

        expireChallenge(ceremonyId);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(randomBytes(16)),
                "rawId", B64URL.encodeToString(randomBytes(16)),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                        "attestationObject", B64URL.encodeToString(new byte[]{0})));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));
    }

    @Test
    void authenticationResultWithExpiredChallengeReturnsChallengeExpiredNotChallengeNotFound() throws Exception {
        String externalUserId = "u-expired-auth-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "Expired Auth Device");

        String optionsResp = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();

        expireChallenge(ceremonyId);

        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x05, 1L, null, null, null);
        byte[] clientDataJson = buildClientDataJson("webauthn.get", challengeB64, ORIGIN);
        byte[] signature = signEcdsa(device.keyPair().getPrivate(), authenticatorData, sha256(clientDataJson));

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(device.credentialId()),
                "rawId", B64URL.encodeToString(device.credentialId()),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        mockMvc.perform(post("/api/v1/authentication/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("ceremonyId", ceremonyId, "credential", credential))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_EXPIRED"));
    }

    // ------------------------------------------------------------------
    // 5. 密碼學邊界情況
    // ------------------------------------------------------------------

    @Test
    void registrationRejectedWhenCredentialPublicKeyDiffersFromLeafCertificatePublicKey() throws Exception {
        String externalUserId = "u-pubkey-mismatch-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Pubkey Mismatch Test"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();
        byte[] challengeBytes = B64URL_DEC.decode(challengeB64);

        // 兩把不同金鑰：credentialKeyPair 宣告在 authenticatorData.credentialPublicKey，
        // leafKeyPair 才是 leaf 憑證真正的公私鑰對（也用來簽 attStmt.sig，確保簽章本身合法，
        // 純粹測試「兩者不一致」這個檢查點，而不是連簽章驗證都通不過）。
        KeyPair credentialKeyPair = generateEcKeyPair();
        KeyPair leafKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41, 0L, new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        X509Certificate leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                leafKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX, challengeBytes);
        byte[] attStmtSig = signEcdsa(leafKeyPair.getPrivate(), authenticatorData, clientDataHash);
        byte[] attestationObject = buildAttestationObject("android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", List.of("internal")));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ATTESTATION_INVALID"));

        mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));
    }

    @Test
    void registrationRejectedWhenAttStmtSignatureIsTamperedByOneByte() throws Exception {
        String externalUserId = "u-tampered-sig-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Tampered Sig Test"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();
        byte[] challengeBytes = B64URL_DEC.decode(challengeB64);

        KeyPair credentialKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41, 0L, new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        X509Certificate leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                credentialKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX, challengeBytes);
        byte[] attStmtSig = signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);
        attStmtSig[attStmtSig.length - 1] ^= 0x01; // 竄改 1 byte
        byte[] attestationObject = buildAttestationObject("android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", List.of("internal")));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ATTESTATION_INVALID"));
    }

    @Test
    void registrationRejectedWhenX5cTerminatesInUntrustedRoot() throws Exception {
        String externalUserId = "u-untrusted-root-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Untrusted Root Test"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();
        byte[] challengeBytes = B64URL_DEC.decode(challengeB64);

        KeyPair credentialKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41, 0L, new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        // 另一組完全獨立、自簽的 root（不是 TestKeyAttestationFixtures.ROOT_CERTIFICATE，
        // 也就是測試信任錨點以外的 root），模擬攻擊者用自己的 root 簽發整條鏈的情境。
        KeyPair untrustedRootKeyPair = generateEcKeyPair();
        X509Certificate untrustedRoot = selfSignRoot(untrustedRootKeyPair, "CN=Untrusted Test Root,O=Attacker");
        X509Certificate leafCert = buildLeafCertificateSignedBy(
                credentialKeyPair.getPublic(), untrustedRootKeyPair, untrustedRoot.getSubjectX500Principal().getName(),
                TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX, challengeBytes);
        byte[] attStmtSig = signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);

        Map<String, Object> attStmt = new LinkedHashMap<>();
        attStmt.put("alg", -7);
        attStmt.put("sig", attStmtSig);
        attStmt.put("x5c", List.of(leafCert.getEncoded(), untrustedRoot.getEncoded()));
        Map<String, Object> attestationObjectMap = new LinkedHashMap<>();
        attestationObjectMap.put("fmt", "android-key");
        attestationObjectMap.put("authData", authenticatorData);
        attestationObjectMap.put("attStmt", attStmt);
        byte[] attestationObject = cborMapper.writeValueAsBytes(attestationObjectMap);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", List.of("internal")));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ATTESTATION_CHAIN_INVALID"));
    }

    @Test
    void authenticationRejectedWhenAssertionSignatureTamperedAndNotMisclassifiedAsCounterIssue() throws Exception {
        String externalUserId = "u-tampered-assertion-" + RANDOM.nextInt(1_000_000);
        RegisteredDevice device = registerDevice(externalUserId, "Tampered Assertion Device");

        String authOptionsResp = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andReturn().getResponse().getContentAsString();
        JsonNode authOptionsJson = jsonMapper.readTree(authOptionsResp);
        String authCeremonyId = authOptionsJson.get("ceremonyId").asText();
        String authChallengeB64 = authOptionsJson.get("publicKey").get("challenge").asText();

        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x05, 1L, null, null, null);
        byte[] clientDataJson = buildClientDataJson("webauthn.get", authChallengeB64, ORIGIN);
        byte[] signature = signEcdsa(device.keyPair().getPrivate(), authenticatorData, sha256(clientDataJson));
        signature[signature.length - 1] ^= 0x01; // 竄改 1 byte

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(device.credentialId()),
                "rawId", B64URL.encodeToString(device.credentialId()),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        mockMvc.perform(post("/api/v1/authentication/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("ceremonyId", authCeremonyId, "credential", credential))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASSERTION_INVALID"));

        // 確認沒有被誤判成 sign counter 異常而遭自動撤銷：裝置應仍是 ACTIVE，且正常簽章的登入仍可成功。
        mockMvc.perform(get("/api/v1/users/{id}/devices", externalUserId)
                        .header("X-API-Key", API_KEY).param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].status").value("ACTIVE"));

        loginAttempt(externalUserId, device.credentialId(), device.keyPair().getPrivate(), 1L)
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 不帶 tag 的 counter 目前值；尚未有任何事件發生過時 find(...).counter() 回傳 null，視為 0。 */
    private double counterCount(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** {@code fido.auth.verify.failures}{@code {reason=<errorCode>}} 目前值，同上、找不到視為 0。 */
    private double authVerifyFailuresCount(String errorCodeName) {
        Counter counter = meterRegistry.find(MetricNames.AUTH_VERIFY_FAILURES)
                .tag(MetricNames.TAG_REASON, errorCodeName)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private record RegisteredDevice(String externalUserId, byte[] credentialId, KeyPair keyPair, String deviceId) {
    }

    private RegisteredDevice registerDevice(String externalUserId, String deviceLabel) throws Exception {
        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Test User", "deviceLabel", deviceLabel))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();
        byte[] challengeBytes = B64URL_DEC.decode(challengeB64);

        KeyPair credentialKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41, 0L, new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        X509Certificate leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                credentialKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX, challengeBytes);
        byte[] attStmtSig = signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);
        byte[] attestationObject = buildAttestationObject("android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", List.of("internal")));

        String resultResp = mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential,
                                "deviceLabel", deviceLabel))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String deviceId = jsonMapper.readTree(resultResp).get("deviceId").asText();

        return new RegisteredDevice(externalUserId, credentialId, credentialKeyPair, deviceId);
    }

    private ResultActions loginAttempt(String externalUserId, byte[] credentialId, PrivateKey credentialPrivateKey,
                                        long signCount) throws Exception {
        String authOptionsResp = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andReturn().getResponse().getContentAsString();
        JsonNode authOptionsJson = jsonMapper.readTree(authOptionsResp);
        String ceremonyId = authOptionsJson.get("ceremonyId").asText();
        String challengeB64 = authOptionsJson.get("publicKey").get("challenge").asText();

        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x05, signCount, null, null, null);
        byte[] clientDataJson = buildClientDataJson("webauthn.get", challengeB64, ORIGIN);
        byte[] signature = signEcdsa(credentialPrivateKey, authenticatorData, sha256(clientDataJson));

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        String body = jsonMapper.writeValueAsString(Map.of("ceremonyId", ceremonyId, "credential", credential));

        return mockMvc.perform(post("/api/v1/authentication/result")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void expireChallenge(String ceremonyId) {
        AuthChallenge challenge = authChallengeRepository.findByCeremonyId(ceremonyId).orElseThrow();
        challenge.setExpiresAt(Instant.now().minusSeconds(5));
        authChallengeRepository.save(challenge);
    }

    private byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    private byte[] buildClientDataJson(String type, String challengeB64, String origin) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("challenge", challengeB64);
        map.put("origin", origin);
        return jsonMapper.writeValueAsString(map).getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        return keyPairGenerator.generateKeyPair();
    }

    private byte[] buildEcCoseKeyBytes(ECPublicKey publicKey) throws Exception {
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1, 2);
        cose.put(3, -7);
        cose.put(-1, 1);
        cose.put(-2, toFixedLength(publicKey.getW().getAffineX(), 32));
        cose.put(-3, toFixedLength(publicKey.getW().getAffineY(), 32));
        return cborMapper.writeValueAsBytes(cose);
    }

    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] fixed = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, fixed, 0, length);
        } else {
            System.arraycopy(raw, 0, fixed, length - raw.length, raw.length);
        }
        return fixed;
    }

    private static byte[] signEcdsa(PrivateKey privateKey, byte[]... dataParts) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        for (byte[] part : dataParts) {
            signature.update(part);
        }
        return signature.sign();
    }

    private byte[] buildAuthenticatorData(String rpId, byte flags, long signCount, byte[] aaguid,
                                           byte[] credentialId, byte[] coseKeyBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)));
        out.write(flags);
        out.write(ByteBuffer.allocate(4).putInt((int) signCount).array());
        if ((flags & 0x40) != 0) {
            out.write(aaguid);
            out.write(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
            out.write(credentialId);
            out.write(coseKeyBytes);
        }
        return out.toByteArray();
    }

    private byte[] buildAttestationObject(String fmt, byte[] authenticatorData, byte[] attStmtSig,
                                           X509Certificate leafCert) throws Exception {
        Map<String, Object> attStmt = new LinkedHashMap<>();
        attStmt.put("alg", -7);
        attStmt.put("sig", attStmtSig);
        attStmt.put("x5c", List.of(leafCert.getEncoded(), TestKeyAttestationFixtures.ROOT_CERTIFICATE.getEncoded()));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fmt", fmt);
        map.put("authData", authenticatorData);
        map.put("attStmt", attStmt);
        return cborMapper.writeValueAsBytes(map);
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    /**
     * 自簽一張獨立的測試 root 憑證，刻意與 {@link TestKeyAttestationFixtures#ROOT_CERTIFICATE}
     * 無關，用於「x5c 鏈尾非受信任 root」情境。
     */
    private static X509Certificate selfSignRoot(KeyPair rootKeyPair, String subjectDn) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name(subjectDn);
        BigInteger serial = BigInteger.valueOf(now.toEpochMilli());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial,
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(3650))),
                name, rootKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(rootKeyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    /** 用指定的（非受信任）root 金鑰對簽發一張帶 Key Attestation extension 的 leaf 憑證。 */
    private static X509Certificate buildLeafCertificateSignedBy(PublicKey leafPublicKey, KeyPair signingRootKeyPair,
                                                                  String issuerDn, int securityLevel,
                                                                  byte[] attestationChallenge) throws Exception {
        Instant now = Instant.now();
        X500Name issuer = new X500Name(issuerDn);
        X500Name subject = new X500Name("CN=Untrusted Test Leaf Key,O=Attacker");
        BigInteger serial = BigInteger.valueOf(now.toEpochMilli());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial,
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(1))),
                subject, leafPublicKey);

        ASN1EncodableVector keyDescription = new ASN1EncodableVector();
        keyDescription.add(new ASN1Integer(4));
        keyDescription.add(new ASN1Enumerated(securityLevel));
        keyDescription.add(new ASN1Integer(4));
        keyDescription.add(new ASN1Enumerated(securityLevel));
        keyDescription.add(new DEROctetString(attestationChallenge != null ? attestationChallenge : new byte[0]));
        keyDescription.add(new DEROctetString(new byte[0]));
        keyDescription.add(new DERSequence());
        keyDescription.add(new DERSequence());
        builder.addExtension(new ASN1ObjectIdentifier(KEY_ATTESTATION_EXTENSION_OID), false,
                new DERSequence(keyDescription));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(signingRootKeyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
