package com.fido.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端對端骨架測試：涵蓋註冊 -&gt; 登入 -&gt; sign counter 倒退自動撤銷 -&gt; 裝置管理 -&gt; 防列舉
 * -&gt; API Key 認證的完整路徑。attestation/assertion 密碼學驗證因走 stub 介面卡而恆為通過，
 * 本測試驗證的是「骨架語意」（challenge 時效、sign counter、防列舉、軟刪除等），非真實密碼學。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationAndAuthenticationFlowTest {

    private static final String API_KEY = "dev-api-key-00000000000000000000";
    private static final String RP_ID = "shop.example.com";
    private static final String ORIGIN = "https://shop.example.com";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
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

        // ---- 2. 註冊：組出 credential，送出 result ----
        byte[] credentialId = randomBytes(32);
        byte[] coseKeyBytes = buildCoseKeyBytes();
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x41 /* UP+AT */, 0L,
                new byte[16], credentialId, coseKeyBytes);
        byte[] attestationObject = buildAttestationObject("android-key", authenticatorData);
        byte[] clientDataJson = buildClientDataJson("webauthn.create", challengeB64, ORIGIN);

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

        // ---- 4. 登入：正常路徑（sign counter 0 -> 1） ----
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

        submitAssertionAndExpect(externalUserId, credentialId, authCeremonyId, authChallengeB64, 1L, status().isOk());

        // ---- 5. Sign counter 倒退 -> 422 SIGN_COUNTER_REGRESSION，且憑證/裝置被自動撤銷 ----
        String authOptionsResp2 = mockMvc.perform(post("/api/v1/authentication/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("externalUserId", externalUserId))))
                .andReturn().getResponse().getContentAsString();
        JsonNode authOptionsJson2 = jsonMapper.readTree(authOptionsResp2);
        String authCeremonyId2 = authOptionsJson2.get("ceremonyId").asText();
        String authChallengeB64_2 = authOptionsJson2.get("publicKey").get("challenge").asText();

        submitAssertionAndExpect(externalUserId, credentialId, authCeremonyId2, authChallengeB64_2, 0L,
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

    private void submitAssertionAndExpect(String externalUserId, byte[] credentialId, String ceremonyId,
                                           String challengeB64, long signCount,
                                           org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        byte[] authenticatorData = buildAuthenticatorData(RP_ID, (byte) 0x05 /* UP+UV */, signCount,
                null, null, null);
        byte[] clientDataJson = buildClientDataJson("webauthn.get", challengeB64, ORIGIN);
        byte[] signature = randomBytes(64);

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

    private byte[] buildCoseKeyBytes() throws Exception {
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1, 2);   // kty: EC2
        cose.put(3, -7);  // alg: ES256
        cose.put(-1, 1);  // crv: P-256
        cose.put(-2, randomBytes(32)); // x
        cose.put(-3, randomBytes(32)); // y
        return cborMapper.writeValueAsBytes(cose);
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

    private byte[] buildAttestationObject(String fmt, byte[] authenticatorData) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fmt", fmt);
        map.put("authData", authenticatorData);
        map.put("attStmt", new LinkedHashMap<>());
        return cborMapper.writeValueAsBytes(map);
    }
}
