package com.fido.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fido.server.testsupport.TestKeyAttestationFixtures;
import com.fido.server.webauthn.TrustedRootCertificateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端對端測試：涵蓋註冊 -&gt; 登入 -&gt; sign counter 倒退自動撤銷 -&gt; 裝置管理 -&gt; 防列舉
 * -&gt; API Key 認證的完整路徑。
 *
 * <p>attestationObject / assertion 皆以真實 EC (P-256) 金鑰對組出「密碼學上合法」的資料
 * （見 {@link TestKeyAttestationFixtures}），走的是
 * {@code fido.attestation.mode=real}（預設）的真實驗證路徑，而非 stub；Android Key
 * Attestation 憑證鏈以本測試自簽的測試 root 為信任錨點（透過下方
 * {@link TestAttestationRootConfig} 把 {@link TrustedRootCertificateStore} 換成測試版本，
 * 不影響正式部署內建的 Google root 信任集合）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationAndAuthenticationFlowTest.TestAttestationRootConfig.class)
class RegistrationAndAuthenticationFlowTest {

    private static final String API_KEY = "dev-api-key-00000000000000000000";
    private static final String RP_ID = "shop.example.com";
    private static final String ORIGIN = "https://shop.example.com";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    @Test
    void fullRegistrationAndAuthenticationFlow() throws Exception {
        String externalUserId = "u-test-" + RANDOM.nextInt(1_000_000);

        // ---- 1. 註冊：取得 options ----
        String optionsBody = jsonMapper.writeValueAsString(Map.of(
                "externalUserId", externalUserId,
                "displayName", "Test User",
                "deviceLabel", "My Test Device"));

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(optionsBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ceremonyId").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();

        // ---- 2. 註冊：組出「密碼學上合法」的 credential，送出 result ----
        // 本次註冊憑證的實際金鑰對：attStmt.x5c[0]（leaf 憑證）與
        // authenticatorData.credentialPublicKey 都指向同一把公鑰，attStmt.sig 與登入 assertion
        // 皆由對應私鑰簽出，確保走的是 RealAttestationStatementVerifier /
        // RealAndroidKeyAttestationChainValidator / RealAssertionSignatureVerifier 的真實驗證路徑。
        KeyPair credentialKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41 /* UP+AT */, 0L,
                new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        byte[] challengeBytes = B64URL_DEC.decode(challengeB64);
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
                        "transports", java.util.List.of("internal")));

        String resultBody = jsonMapper.writeValueAsString(Map.of(
                "ceremonyId", ceremonyId,
                "externalUserId", externalUserId,
                "credential", credential,
                "deviceLabel", "My Test Device"));

        String resultResp = mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.securityLevel").value("STRONG_BOX"))
                .andExpect(jsonPath("$.signCount").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode regResultJson = jsonMapper.readTree(resultResp);
        String deviceId = regResultJson.get("deviceId").asText();

        // ---- 3. fido-status 應顯示已綁定 ----
        mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.activeDeviceCount").value(1));

        // ---- 4. 登入：正常路徑（sign counter 0 -> 1），assertion 簽章以同一把私鑰真實簽出 ----
        String authOptionsResp = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.allowCredentials[0].id").value(B64URL.encodeToString(credentialId)))
                .andReturn().getResponse().getContentAsString();

        JsonNode authOptionsJson = jsonMapper.readTree(authOptionsResp);
        String authCeremonyId = authOptionsJson.get("ceremonyId").asText();
        String authChallengeB64 = authOptionsJson.get("publicKey").get("challenge").asText();

        submitAssertionAndExpect(credentialId, credentialKeyPair.getPrivate(), authCeremonyId, authChallengeB64, 1L,
                status().isOk());

        // ---- 5. Sign counter 倒退 -> 422 SIGN_COUNTER_REGRESSION，且憑證/裝置被自動撤銷 ----
        // （signature 仍須合法簽出才能通過簽章驗證，才輪到 sign counter 語意檢查——
        // 這正是本次把 stub 換成真實密碼學後，此案例真正驗證到的行為。）
        String authOptionsResp2 = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andReturn().getResponse().getContentAsString();
        JsonNode authOptionsJson2 = jsonMapper.readTree(authOptionsResp2);
        String authCeremonyId2 = authOptionsJson2.get("ceremonyId").asText();
        String authChallengeB64_2 = authOptionsJson2.get("publicKey").get("challenge").asText();

        submitAssertionAndExpect(credentialId, credentialKeyPair.getPrivate(), authCeremonyId2, authChallengeB64_2, 0L,
                status().isUnprocessableEntity());

        // 裝置已被自動撤銷 -> fido-status 應變回未啟用
        mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));

        // ---- 6. 裝置列表：ALL 應可看到 REVOKED 裝置 ----
        mockMvc.perform(get("/api/v1/users/{id}/devices", externalUserId)
                        .header("X-API-Key", API_KEY)
                        .param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].status").value("REVOKED"));

        // ---- 7. 撤銷裝置：冪等 no-op（裝置已是 REVOKED），仍回 200 REVOKED ----
        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", externalUserId, deviceId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // ---- 8. 撤銷不存在的裝置：仍回 200（防列舉，不回 404） ----
        mockMvc.perform(delete("/api/v1/users/{id}/devices/{deviceId}", externalUserId, java.util.UUID.randomUUID())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void registrationFailsWithHardwareSecurityNotMetWhenLeafDeclaresSoftwareLevel() throws Exception {
        // 對齊 CLAUDE.md「強制要求 TEE/StrongBox，不通過則拒絕註冊」：leaf 憑證宣稱
        // Software（0）等級時，即使憑證鏈本身合法、簽章也合法，仍必須被拒絕、不落庫。
        String externalUserId = "u-test-sw-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Test User"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();

        KeyPair credentialKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41, 0L, new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        X509Certificate leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                credentialKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_SOFTWARE,
                B64URL_DEC.decode(challengeB64));
        byte[] attStmtSig = signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);
        byte[] attestationObject = buildAttestationObject("android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", java.util.List.of("internal")));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("HARDWARE_SECURITY_NOT_MET"));
    }

    @Test
    void registrationFailsWithAttestationChainInvalidWhenAttestationChallengeMismatchesCeremonyChallenge() throws Exception {
        // 對齊「加 challenge 比對」的決策：leaf 憑證 Key Attestation extension 內的
        // attestationChallenge 若不等於本次 ceremony 核發的 challenge（例如攻擊者重放另一次
        // ceremony 產生的合法憑證鏈與簽章），即使憑證鏈本身合法、簽章也合法、安全等級也達標，
        // 仍必須被拒絕、不落庫，且錯誤原因需能與其他失敗原因（如 HARDWARE_SECURITY_NOT_MET）區分。
        String externalUserId = "u-test-challenge-mismatch-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "Test User"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();

        KeyPair credentialKeyPair = generateEcKeyPair();
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildEcCoseKeyBytes((ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41, 0L, new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = sha256(clientDataJson);

        // leaf 憑證的 attestationChallenge 刻意寫入一組與本次 ceremony 無關的隨機 bytes，
        // 而非 B64URL_DEC.decode(challengeB64)，模擬 challenge 不符的情境。
        X509Certificate leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                credentialKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX,
                randomBytes(32));
        byte[] attStmtSig = signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);
        byte[] attestationObject = buildAttestationObject("android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", java.util.List.of("internal")));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "ceremonyId", ceremonyId,
                                "externalUserId", externalUserId,
                                "credential", credential))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ATTESTATION_CHAIN_INVALID"));

        // 未落庫：fido-status 應仍顯示未啟用。
        mockMvc.perform(get("/api/v1/users/{id}/fido-status", externalUserId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));
    }

    @Test
    void authenticationOptionsForUnknownUserDoesNotLeak404() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", "no-such-user-xyz"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.allowCredentials").isEmpty());
    }

    @Test
    void fidoStatusForUnknownUserReturns200NotEnrolled() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/fido-status", "no-such-user-xyz").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.canUseFido").value(false));
    }

    @Test
    void missingApiKeyReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/registration/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", "someone"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidApiKeyReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", "someone"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownCeremonyIdReturnsChallengeNotFound() throws Exception {
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
                                "ceremonyId", "reg_does_not_exist",
                                "externalUserId", "someone",
                                "credential", credential))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_NOT_FOUND"));
    }

    @Test
    void jwksEndpointIsPublicAndReturnsEcKey() throws Exception {
        mockMvc.perform(get("/api/v1/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void submitAssertionAndExpect(byte[] credentialId, PrivateKey credentialPrivateKey, String ceremonyId,
                                           String challengeB64, long signCount,
                                           ResultMatcher expectedStatus) throws Exception {
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x05 /* UP+UV */, signCount,
                null, null, null);
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

        mockMvc.perform(post("/api/v1/authentication/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expectedStatus);
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

    /** 把真實 EC (P-256) 公鑰編碼為 COSE_Key（EC2, alg=ES256）CBOR bytes。 */
    private byte[] buildEcCoseKeyBytes(ECPublicKey publicKey) throws Exception {
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1, 2);   // kty: EC2
        cose.put(3, -7);  // alg: ES256
        cose.put(-1, 1);  // crv: P-256
        cose.put(-2, toFixedLength(publicKey.getW().getAffineX(), 32)); // x
        cose.put(-3, toFixedLength(publicKey.getW().getAffineY(), 32)); // y
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
     * 把 {@link TrustedRootCertificateStore} 換成僅包含本測試自簽 root 的版本，讓
     * {@code RealAndroidKeyAttestationChainValidator} 能對 {@link TestKeyAttestationFixtures}
     * 組出的憑證鏈驗證通過，而不需要（也不可能）持有 Google 的 attestation root 私鑰。
     * {@code @TestConfiguration} 不會被主應用程式的 component scan 撿到，僅在本測試
     * 透過 {@code @Import} 顯式引入時生效，不影響正式部署。
     */
    @TestConfiguration
    static class TestAttestationRootConfig {
        @Bean
        @Primary
        TrustedRootCertificateStore testTrustedRootCertificateStore() {
            return new TrustedRootCertificateStore(java.util.List.of(TestKeyAttestationFixtures.ROOT_CERTIFICATE));
        }
    }
}
