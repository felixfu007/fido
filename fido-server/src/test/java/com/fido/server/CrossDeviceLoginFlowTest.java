package com.fido.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fido.server.testsupport.TestKeyAttestationFixtures;
import com.fido.server.testsupport.WebAuthnCeremonyFixtures;
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

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端對端測試：跨裝置 QR 登入（情境三，api-contract.md §3.4）五個端點 A-E 的完整正常路徑
 * （真實密碼學 assertion 簽章，重用 {@link WebAuthnCeremonyFixtures}），以及主要負向路徑
 * （session 不存在/狀態不符、assertion 簽章驗證失敗、端點 E 對終端成功狀態的 409 保護）。逾時
 * （EXPIRED）與跨租戶隔離、token 暫存單機限制、端點 E 的冪等/reason 正規化等更方便用 mock 構造的
 * 邊界情況見 {@link CrossDeviceLoginServiceTest}。
 *
 * <p>先透過既有註冊端點（§2）建立一個「真的」已註冊裝置/憑證，再用同一把私鑰在 cross-device
 * 端點 C 簽出 assertion，確保走的是 {@code fido.attestation.mode=real} 的真實驗證路徑。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CrossDeviceLoginFlowTest.TestAttestationRootConfig.class)
class CrossDeviceLoginFlowTest {

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
    void fullCrossDeviceLoginFlowAcrossAllFourEndpoints() throws Exception {
        RegisteredCredential registered = registerCredential();

        // ---- A：購物網站後端建立 xdev session ----
        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andExpect(jsonPath("$.xdevId").exists())
                .andExpect(jsonPath("$.qrUrl").exists())
                .andExpect(jsonPath("$.verificationCode").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode createJson = jsonMapper.readTree(createResp);
        String xdevId = createJson.get("xdevId").asText();
        String verificationCodeAtCreate = createJson.get("verificationCode").asText();

        // ---- B：手機 App claim（不帶 X-API-Key）----
        String claimResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value(RP_ID))
                .andExpect(jsonPath("$.origin").value(ORIGIN))
                .andExpect(jsonPath("$.verificationCode").value(verificationCodeAtCreate))
                .andReturn().getResponse().getContentAsString();
        JsonNode claimJson = jsonMapper.readTree(claimResp);
        String challengeB64 = claimJson.get("challenge").asText();

        // ---- C：手機 App 送出真實 assertion（不帶 X-API-Key），桌機/手機同 IP -> proximity 相符 ----
        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(
                RP_ID, (byte) 0x05 /* UP+UV */, 1L, null, null, null);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.get", challengeB64, ORIGIN);
        byte[] signature = WebAuthnCeremonyFixtures.signEcdsa(registered.privateKey(), authenticatorData,
                WebAuthnCeremonyFixtures.sha256(clientDataJson));

        Map<String, Object> assertionBody = Map.of(
                "id", B64URL.encodeToString(registered.credentialId()),
                "rawId", B64URL.encodeToString(registered.credentialId()),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/result", xdevId)
                        .with(remoteAddr("203.0.113.5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(assertionBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.proximity.checked").value(true))
                .andExpect(jsonPath("$.proximity.mismatch").value(false));

        // ---- D：購物網站後端輪詢，取得 session JWT（amr 含 xdev）並立即轉 CONSUMED ----
        String statusResp = mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", xdevId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.session.token").exists())
                .andExpect(jsonPath("$.session.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.warnings.proximityMismatch").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode statusJson = jsonMapper.readTree(statusResp);
        String jwt = statusJson.get("session").get("token").asText();
        assertAmrContainsXdev(jwt);

        // ---- D 再次輪詢：JWT 只能被領一次 -> 409 XDEV_SESSION_INVALID_STATE ----
        mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", xdevId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_INVALID_STATE"));
    }

    @Test
    void proximityMismatchIsFlaggedButLoginStillSucceeds() throws Exception {
        RegisteredCredential registered = registerCredential();

        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        String claimResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId))
                .andReturn().getResponse().getContentAsString();
        String challengeB64 = jsonMapper.readTree(claimResp).get("challenge").asText();

        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(
                RP_ID, (byte) 0x05, 1L, null, null, null);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.get", challengeB64, ORIGIN);
        byte[] signature = WebAuthnCeremonyFixtures.signEcdsa(registered.privateKey(), authenticatorData,
                WebAuthnCeremonyFixtures.sha256(clientDataJson));
        Map<String, Object> assertionBody = Map.of(
                "id", B64URL.encodeToString(registered.credentialId()),
                "rawId", B64URL.encodeToString(registered.credentialId()),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        // 手機直連來源 IP（198.51.100.9）與桌機發起 IP（203.0.113.5）不同 -> 只標記，不阻擋。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/result", xdevId)
                        .with(remoteAddr("198.51.100.9"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(assertionBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.proximity.mismatch").value(true));

        mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", xdevId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.session.token").exists())
                .andExpect(jsonPath("$.warnings.proximityMismatch").value(true));
    }

    @Test
    void claimOnUnknownXdevIdReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", "does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_NOT_FOUND"));
    }

    @Test
    void claimEndpointDoesNotRequireApiKey() throws Exception {
        // 沒有 X-API-Key header，仍應命中 service 層邏輯（回 404 而非 401），證明 claim 端點確實
        // 走 capability 認證、不受 ApiKeyAuthFilter 一般租戶認證管制（api-contract.md D16）。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", "unknown-xdev"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_NOT_FOUND"));
    }

    @Test
    void claimingTwiceOnSameSessionReturnsInvalidState() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_INVALID_STATE"));
    }

    @Test
    void resultBeforeClaimReturnsInvalidState() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        Map<String, Object> bogusAssertion = Map.of(
                "id", B64URL.encodeToString(WebAuthnCeremonyFixtures.randomBytes(16)),
                "rawId", B64URL.encodeToString(WebAuthnCeremonyFixtures.randomBytes(16)),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString("{}".getBytes()),
                        "authenticatorData", B64URL.encodeToString(new byte[]{0}),
                        "signature", B64URL.encodeToString(new byte[]{0})));

        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/result", xdevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(bogusAssertion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_INVALID_STATE"));
    }

    @Test
    void resultWithInvalidSignatureFailsAssertionValidationAndSessionStaysScanned() throws Exception {
        RegisteredCredential registered = registerCredential();

        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        String claimResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId))
                .andReturn().getResponse().getContentAsString();
        String challengeB64 = jsonMapper.readTree(claimResp).get("challenge").asText();

        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(
                RP_ID, (byte) 0x05, 1L, null, null, null);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.get", challengeB64, ORIGIN);
        // 刻意用一組隨機 bytes 當簽章，而非對 authenticatorData||clientDataHash 真實簽出。
        byte[] garbageSignature = WebAuthnCeremonyFixtures.randomBytes(64);

        Map<String, Object> assertionBody = Map.of(
                "id", B64URL.encodeToString(registered.credentialId()),
                "rawId", B64URL.encodeToString(registered.credentialId()),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(garbageSignature)));

        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/result", xdevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(assertionBody)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASSERTION_INVALID"));

        // session 應仍是 SCANNED（容許重試），而非被誤轉成 CONFIRMED；輪詢應顯示 SCANNED。
        mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", xdevId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"));
    }

    @Test
    void denyEndpointDoesNotRequireApiKeyAndUnknownXdevIdReturns404() throws Exception {
        // 沒有 X-API-Key header，仍應命中 service 層邏輯（回 404 而非 401），證明 deny 端點確實
        // 走 capability 認證、不受 ApiKeyAuthFilter 一般租戶認證管制（api-contract.md D16）。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/deny", "unknown-xdev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_NOT_FOUND"));
    }

    @Test
    void denyEndpointAcceptsMissingRequestBody() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        // 完全不帶 body（reason 全選填），仍應成功轉 DENIED。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/deny", xdevId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));
    }

    @Test
    void denyPendingSessionThenDesktopPollSeesDeniedAndSecondDenyIsIdempotent() throws Exception {
        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId))
                .andExpect(status().isOk());

        // 手機端主動放棄（使用者取消）。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/deny", xdevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("reason", "USER_CANCELLED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));

        // 桌機下次 poll（端點 D）應看到 DENIED，端點 D 本身無需任何改動（api-contract.md §3.4.E）。
        mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", xdevId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));

        // 冪等：再次呼叫 deny 仍回 200。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/deny", xdevId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("reason", "NO_CREDENTIAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));
    }

    @Test
    void denyOnConfirmedSessionReturns409AndDoesNotDisturbAlreadyCompletedLogin() throws Exception {
        RegisteredCredential registered = registerCredential();

        String createResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("desktopClientIp", "203.0.113.5"))))
                .andReturn().getResponse().getContentAsString();
        String xdevId = jsonMapper.readTree(createResp).get("xdevId").asText();

        String claimResp = mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/claim", xdevId))
                .andReturn().getResponse().getContentAsString();
        String challengeB64 = jsonMapper.readTree(claimResp).get("challenge").asText();

        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(
                RP_ID, (byte) 0x05, 1L, null, null, null);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.get", challengeB64, ORIGIN);
        byte[] signature = WebAuthnCeremonyFixtures.signEcdsa(registered.privateKey(), authenticatorData,
                WebAuthnCeremonyFixtures.sha256(clientDataJson));
        Map<String, Object> assertionBody = Map.of(
                "id", B64URL.encodeToString(registered.credentialId()),
                "rawId", B64URL.encodeToString(registered.credentialId()),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "authenticatorData", B64URL.encodeToString(authenticatorData),
                        "signature", B64URL.encodeToString(signature)));

        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/result", xdevId)
                        .with(remoteAddr("203.0.113.5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(assertionBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 已完成的登入不允許事後翻成 DENIED。
        mockMvc.perform(post("/api/v1/authentication/cross-device/sessions/{id}/deny", xdevId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("XDEV_SESSION_INVALID_STATE"));

        // 桌機仍能正常領取先前已簽發的 session JWT，證明 deny 的失敗嘗試未破壞已完成的登入。
        mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", xdevId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.session.token").exists());
    }

    @Test
    void statusPollingRequiresApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/authentication/cross-device/sessions/{id}/status", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void assertAmrContainsXdev(String jwt) throws Exception {
        String payloadB64 = jwt.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(payloadB64);
        JsonNode payload = jsonMapper.readTree(decoded);
        List<String> amr = jsonMapper.convertValue(payload.get("amr"), List.class);
        org.assertj.core.api.Assertions.assertThat(amr).containsExactly("fido", "hwk", "xdev");
    }

    private record RegisteredCredential(byte[] credentialId, PrivateKey privateKey) {
    }

    /**
     * 透過既有 §2 註冊端點，建立一台「真的」已註冊裝置/憑證（真實 StrongBox 等級 android-key
     * attestation，走 {@code fido.attestation.mode=real}），供 cross-device 端點 C 用同一把
     * 私鑰簽出真實 assertion。
     */
    private RegisteredCredential registerCredential() throws Exception {
        String externalUserId = "u-xdev-" + RANDOM.nextInt(1_000_000);

        String optionsResp = mockMvc.perform(post("/api/v1/registration/options")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "externalUserId", externalUserId, "displayName", "XDev Test User"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode optionsJson = jsonMapper.readTree(optionsResp);
        String ceremonyId = optionsJson.get("ceremonyId").asText();
        String challengeB64 = optionsJson.get("publicKey").get("challenge").asText();

        KeyPair credentialKeyPair = WebAuthnCeremonyFixtures.generateEcKeyPair();
        byte[] credentialId = WebAuthnCeremonyFixtures.randomBytes(32);
        byte[] coseKeyBytes = WebAuthnCeremonyFixtures.buildEcCoseKeyBytes(cborMapper, (ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(RP_ID, (byte) 0x41, 0L,
                new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(jsonMapper, "webauthn.create", challengeB64, ORIGIN);
        byte[] clientDataHash = WebAuthnCeremonyFixtures.sha256(clientDataJson);

        byte[] challengeBytes = Base64.getUrlDecoder().decode(challengeB64);
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
                        "transports", List.of("internal")));

        String resultBody = jsonMapper.writeValueAsString(Map.of(
                "ceremonyId", ceremonyId,
                "externalUserId", externalUserId,
                "credential", credential,
                "deviceLabel", "XDev Test Device"));

        mockMvc.perform(post("/api/v1/registration/result")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultBody))
                .andExpect(status().isCreated());

        return new RegisteredCredential(credentialId, credentialKeyPair.getPrivate());
    }

    /**
     * 同 {@code RegistrationAndAuthenticationFlowTest.TestAttestationRootConfig}：把
     * {@link TrustedRootCertificateStore} 換成僅含本測試自簽 root 的版本。
     */
    @TestConfiguration
    static class TestAttestationRootConfig {
        @Bean
        @Primary
        TrustedRootCertificateStore testTrustedRootCertificateStore() {
            return new TrustedRootCertificateStore(List.of(TestKeyAttestationFixtures.ROOT_CERTIFICATE));
        }
    }
}
