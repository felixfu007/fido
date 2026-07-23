package com.shop.reference.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.reference.exception.ShopExceptionHandler;
import com.shop.reference.fidoclient.FidoServerApiException;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.RegistrationOptionsResponse;
import com.shop.reference.fidoclient.dto.RegistrationResultRequest;
import com.shop.reference.fidoclient.dto.RegistrationResultResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 對應任務要求「購物網站後端的代理邏輯本身...要有自動化測試覆蓋」：本測試只載入
 * {@link RegistrationProxyController}（{@code @WebMvcTest} 切片）與
 * {@link ShopExceptionHandler}（{@code @RestControllerAdvice} 會被自動掃入切片），
 * 用 {@code @MockBean} 頂替 {@link FidoServerClient}，驗證：
 * <ul>
 *   <li>options / result 兩個端點會原封轉呼叫 {@link FidoServerClient} 並把回應轉交前端；</li>
 *   <li>{@code result} 成功時回 201（對齊 docs/api-contract.md 2.2）；</li>
 *   <li>{@link FidoServerClient} 拋出 {@link FidoServerApiException} 時，
 *       {@link ShopExceptionHandler} 會轉譯成對應 HTTP 狀態碼與 {@code source=FIDO_SERVER}
 *       的錯誤 body，而不是讓例外一路變成籠統的 500。</li>
 * </ul>
 */
@WebMvcTest(RegistrationProxyController.class)
class RegistrationProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FidoServerClient fidoServerClient;

    @Test
    void options_forwardsRequestAndReturnsFidoServerResponse() throws Exception {
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

        mockMvc.perform(post("/shop/api/fido/registration/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "u-10023", "displayName": "Demo", "deviceLabel": "我的裝置" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ceremonyId").value("reg_9c2f"))
                .andExpect(jsonPath("$.publicKey.rp.id").value("shop.example.com"));
    }

    @Test
    void options_missingExternalUserId_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/shop/api/fido/registration/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void result_success_returns201() throws Exception {
        RegistrationResultResponse fidoResponse = new RegistrationResultResponse(
                "cred-abc", "dev-xyz",
                new RegistrationResultResponse.DeviceInfo("我的 Pixel 8", "aaguid-1", "STRONG_BOX", "2026-07-21T08:00:03Z"),
                0L);
        when(fidoServerClient.registrationResult(any())).thenReturn(fidoResponse);

        String body = objectMapper.writeValueAsString(new RegistrationResultRequest(
                "reg_9c2f", "u-10023",
                new RegistrationResultRequest.CredentialAttestation(
                        "cred-abc", "cred-abc", "public-key",
                        new RegistrationResultRequest.AttestationResponse("Y2xpZW50RGF0YQ", "YXR0ZXN0YXRpb24", List.of("internal"))),
                "我的裝置"));

        mockMvc.perform(post("/shop/api/fido/registration/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentialId").value("cred-abc"))
                .andExpect(jsonPath("$.device.securityLevel").value("STRONG_BOX"));
    }

    @Test
    void result_fidoServerRejectsHardwareSecurity_propagatesAs422() throws Exception {
        when(fidoServerClient.registrationResult(any())).thenThrow(new FidoServerApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "HARDWARE_SECURITY_NOT_MET",
                "Attestation security level does not meet TEE/StrongBox requirement.",
                "req-trace-1", Map.of("detectedLevel", "SOFTWARE")));

        String body = objectMapper.writeValueAsString(new RegistrationResultRequest(
                "reg_9c2f", "u-10023",
                new RegistrationResultRequest.CredentialAttestation(
                        "cred-abc", "cred-abc", "public-key",
                        new RegistrationResultRequest.AttestationResponse("Y2xpZW50RGF0YQ", "YXR0ZXN0YXRpb24", List.of("internal"))),
                null));

        mockMvc.perform(post("/shop/api/fido/registration/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.source").value("FIDO_SERVER"))
                .andExpect(jsonPath("$.error.code").value("HARDWARE_SECURITY_NOT_MET"))
                .andExpect(jsonPath("$.error.details.fidoServerTraceId").value("req-trace-1"));
    }
}
