package com.shop.reference.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.reference.exception.ShopExceptionHandler;
import com.shop.reference.fidoclient.FidoServerApiException;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.RegistrationOptionsResponse;
import com.shop.reference.fidoclient.dto.RegistrationResultRequest;
import com.shop.reference.fidoclient.dto.RegistrationResultResponse;
import com.shop.reference.session.ExternalUserIdMismatchException;
import com.shop.reference.session.NotLoggedInException;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 對應任務要求「購物網站後端的代理邏輯本身...要有自動化測試覆蓋」：本測試只載入
 * {@link RegistrationProxyController}（{@code @WebMvcTest} 切片）與
 * {@link ShopExceptionHandler}（{@code @RestControllerAdvice} 會被自動掃入切片），
 * 用 {@code @MockBean} 頂替 {@link FidoServerClient} 與 {@link ShopSessionService}，驗證：
 * <ul>
 *   <li>未登入（沒有/無效的 SHOP_SESSION cookie）呼叫兩個端點都會被拒絕（401）；</li>
 *   <li>已登入時，options / result 會使用 session 內的 externalUserId 呼叫 fido-server，
 *       而不是請求 body 裡的值；</li>
 *   <li>已登入時，如果請求 body 另外夾帶了與 session 不同的 externalUserId，會被拒絕（403），
 *       而不是被沉默忽略；</li>
 *   <li>{@code result} 成功時回 201（對齊 docs/api-contract.md 2.2）；</li>
 *   <li>{@link FidoServerClient} 拋出 {@link FidoServerApiException} 時，
 *       {@link ShopExceptionHandler} 會轉譯成對應 HTTP 狀態碼與 {@code source=FIDO_SERVER}
 *       的錯誤 body，而不是讓例外一路變成籠統的 500。</li>
 * </ul>
 */
@WebMvcTest(RegistrationProxyController.class)
class RegistrationProxyControllerTest {

    private static final String SESSION_ID = "shopsess_test-1";
    private static final String LOGGED_IN_USER = "u-10023";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FidoServerClient fidoServerClient;

    @MockBean
    private ShopSessionService shopSessionService;

    private Cookie sessionCookie() {
        return new Cookie(ShopSessionService.COOKIE_NAME, SESSION_ID);
    }

    /** 模擬「已登入」：requireSession 回傳有效 session，resolveExternalUserId 依真實邏輯行為（相符則放行、不符則丟例外）。 */
    private void mockLoggedIn() {
        ShopSession session = new ShopSession(SESSION_ID, LOGGED_IN_USER, null, null, Instant.now());
        when(shopSessionService.requireSession(SESSION_ID)).thenReturn(session);
        when(shopSessionService.resolveExternalUserId(eq(session), isNull())).thenReturn(LOGGED_IN_USER);
        when(shopSessionService.resolveExternalUserId(eq(session), eq(""))).thenReturn(LOGGED_IN_USER);
        when(shopSessionService.resolveExternalUserId(eq(session), eq(LOGGED_IN_USER))).thenReturn(LOGGED_IN_USER);
        when(shopSessionService.resolveExternalUserId(eq(session), eq("attacker-id")))
                .thenThrow(new ExternalUserIdMismatchException(
                        "請求夾帶的 externalUserId=attacker-id 與登入 session 的 externalUserId=" + LOGGED_IN_USER + " 不一致。"));
    }

    private void mockNotLoggedIn() {
        when(shopSessionService.requireSession(isNull())).thenThrow(
                new NotLoggedInException("尚未登入購物網站，請先登入。"));
    }

    @Test
    void options_notLoggedIn_returns401() throws Exception {
        mockNotLoggedIn();

        mockMvc.perform(post("/shop/api/fido/registration/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "displayName": "Demo", "deviceLabel": "我的裝置" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("NOT_LOGGED_IN"));
    }

    @Test
    void options_loggedIn_usesSessionExternalUserId() throws Exception {
        mockLoggedIn();
        RegistrationOptionsResponse fidoResponse = new RegistrationOptionsResponse(
                "reg_9c2f",
                new RegistrationOptionsResponse.PublicKeyCredentialCreationOptions(
                        new RegistrationOptionsResponse.RpEntity("shop.example.com", "Example Shop"),
                        new RegistrationOptionsResponse.UserEntity("dXNlcg", "u-10023", "Demo"),
                        "Y2hhbGxlbmdl",
                        List.of(new RegistrationOptionsResponse.PubKeyCredParam("public-key", -7)),
                        60000,
                        "direct",
                        new RegistrationOptionsResponse.AuthenticatorSelection("platform", "required", true, "required"),
                        List.of()));
        when(fidoServerClient.registrationOptions(any())).thenReturn(fidoResponse);

        // 請求 body 完全不帶 externalUserId（前端已不再讓使用者輸入這個欄位）。
        mockMvc.perform(post("/shop/api/fido/registration/options")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "displayName": "Demo", "deviceLabel": "我的裝置" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ceremonyId").value("reg_9c2f"))
                .andExpect(jsonPath("$.publicKey.rp.id").value("shop.example.com"));
    }

    @Test
    void options_loggedIn_mismatchedExternalUserId_returns403() throws Exception {
        mockLoggedIn();

        mockMvc.perform(post("/shop/api/fido/registration/options")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "attacker-id", "displayName": "Demo", "deviceLabel": "我的裝置" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_USER_ID_MISMATCH"));
    }

    @Test
    void result_notLoggedIn_returns401() throws Exception {
        mockNotLoggedIn();

        String body = objectMapper.writeValueAsString(new RegistrationResultRequest(
                "reg_9c2f", null,
                new RegistrationResultRequest.CredentialAttestation(
                        "cred-abc", "cred-abc", "public-key",
                        new RegistrationResultRequest.AttestationResponse("Y2xpZW50RGF0YQ", "YXR0ZXN0YXRpb24", List.of("internal"))),
                "我的裝置"));

        mockMvc.perform(post("/shop/api/fido/registration/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("NOT_LOGGED_IN"));
    }

    @Test
    void result_loggedIn_success_returns201() throws Exception {
        mockLoggedIn();
        RegistrationResultResponse fidoResponse = new RegistrationResultResponse(
                "cred-abc", "dev-xyz",
                new RegistrationResultResponse.DeviceInfo("我的 Pixel 8", "aaguid-1", "STRONG_BOX", "2026-07-21T08:00:03Z"),
                0L);
        when(fidoServerClient.registrationResult(any())).thenReturn(fidoResponse);

        String body = objectMapper.writeValueAsString(new RegistrationResultRequest(
                "reg_9c2f", null,
                new RegistrationResultRequest.CredentialAttestation(
                        "cred-abc", "cred-abc", "public-key",
                        new RegistrationResultRequest.AttestationResponse("Y2xpZW50RGF0YQ", "YXR0ZXN0YXRpb24", List.of("internal"))),
                "我的裝置"));

        mockMvc.perform(post("/shop/api/fido/registration/result")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentialId").value("cred-abc"))
                .andExpect(jsonPath("$.device.securityLevel").value("STRONG_BOX"));
    }

    @Test
    void result_loggedIn_mismatchedExternalUserId_returns403() throws Exception {
        mockLoggedIn();

        String body = objectMapper.writeValueAsString(new RegistrationResultRequest(
                "reg_9c2f", "attacker-id",
                new RegistrationResultRequest.CredentialAttestation(
                        "cred-abc", "cred-abc", "public-key",
                        new RegistrationResultRequest.AttestationResponse("Y2xpZW50RGF0YQ", "YXR0ZXN0YXRpb24", List.of("internal"))),
                "我的裝置"));

        mockMvc.perform(post("/shop/api/fido/registration/result")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_USER_ID_MISMATCH"));
    }

    @Test
    void result_fidoServerRejectsHardwareSecurity_propagatesAs422() throws Exception {
        mockLoggedIn();
        when(fidoServerClient.registrationResult(any())).thenThrow(new FidoServerApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "HARDWARE_SECURITY_NOT_MET",
                "Attestation security level does not meet TEE/StrongBox requirement.",
                "req-trace-1", Map.of("detectedLevel", "SOFTWARE")));

        String body = objectMapper.writeValueAsString(new RegistrationResultRequest(
                "reg_9c2f", null,
                new RegistrationResultRequest.CredentialAttestation(
                        "cred-abc", "cred-abc", "public-key",
                        new RegistrationResultRequest.AttestationResponse("Y2xpZW50RGF0YQ", "YXR0ZXN0YXRpb24", List.of("internal"))),
                null));

        mockMvc.perform(post("/shop/api/fido/registration/result")
                        .cookie(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.source").value("FIDO_SERVER"))
                .andExpect(jsonPath("$.error.code").value("HARDWARE_SECURITY_NOT_MET"))
                .andExpect(jsonPath("$.error.details.fidoServerTraceId").value("req-trace-1"));
    }
}
