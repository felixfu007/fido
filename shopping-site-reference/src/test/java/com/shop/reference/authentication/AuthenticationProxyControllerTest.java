package com.shop.reference.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.reference.authentication.jwt.FidoSessionJwtValidator;
import com.shop.reference.authentication.jwt.JwtValidationException;
import com.shop.reference.authentication.jwt.ValidatedFidoSession;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.AuthenticationResultRequest;
import com.shop.reference.fidoclient.dto.AuthenticationResultResponse;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 {@link AuthenticationProxyController} 的核心信任邊界邏輯：只有
 * {@link FidoSessionJwtValidator#validate(String)} 通過，才會建立購物網站 session
 * （{@link ShopSessionService}）並設下 {@code SHOP_SESSION} cookie；JWT 驗證失敗則整個
 * 請求視為失敗（401），不會有任何 session 被建立。
 */
@WebMvcTest(AuthenticationProxyController.class)
class AuthenticationProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FidoServerClient fidoServerClient;

    @MockBean
    private FidoSessionJwtValidator jwtValidator;

    @MockBean
    private ShopSessionService shopSessionService;

    private String requestBody() throws Exception {
        return objectMapper.writeValueAsString(new AuthenticationResultRequest(
                "auth_3d7e",
                new AuthenticationResultRequest.CredentialAssertion(
                        "cred-abc", "cred-abc", "public-key",
                        new AuthenticationResultRequest.AssertionResponse(
                                "Y2xpZW50RGF0YQ", "YXV0aERhdGE", "c2ln", "dXNlckhhbmRsZQ"))));
    }

    @Test
    void result_validJwt_createsShopSessionAndSetsCookie() throws Exception {
        AuthenticationResultResponse fidoResponse = new AuthenticationResultResponse(
                true, "u-10023", "cred-abc", "dev-xyz",
                new AuthenticationResultResponse.SessionInfo("header.payload.signature", "Bearer", 120));
        when(fidoServerClient.authenticationResult(any())).thenReturn(fidoResponse);

        ValidatedFidoSession validated = new ValidatedFidoSession(
                "u-10023", "tenant-uid-1", "cred-abc", "dev-xyz", Instant.now().getEpochSecond(), "jti-1");
        when(jwtValidator.validate("header.payload.signature")).thenReturn(validated);

        ShopSession shopSession = new ShopSession("shopsess_1", "u-10023", "dev-xyz", "cred-abc", Instant.now());
        when(shopSessionService.createSession("u-10023", "dev-xyz", "cred-abc")).thenReturn(shopSession);

        mockMvc.perform(post("/shop/api/fido/authentication/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(true))
                .andExpect(jsonPath("$.externalUserId").value("u-10023"))
                .andExpect(cookie().exists(ShopSessionService.COOKIE_NAME))
                .andExpect(cookie().value(ShopSessionService.COOKIE_NAME, "shopsess_1"))
                .andExpect(cookie().httpOnly(ShopSessionService.COOKIE_NAME, true));
    }

    @Test
    void result_jwtSignatureInvalid_rejectsAndDoesNotCreateSession() throws Exception {
        AuthenticationResultResponse fidoResponse = new AuthenticationResultResponse(
                true, "u-10023", "cred-abc", "dev-xyz",
                new AuthenticationResultResponse.SessionInfo("tampered.jwt.here", "Bearer", 120));
        when(fidoServerClient.authenticationResult(any())).thenReturn(fidoResponse);
        when(jwtValidator.validate(anyString()))
                .thenThrow(new JwtValidationException("SIGNATURE_OR_CLAIM_INVALID", "簽章驗證失敗"));

        mockMvc.perform(post("/shop/api/fido/authentication/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.source").value("SHOP_JWT_CHECK"))
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_OR_CLAIM_INVALID"))
                .andExpect(cookie().doesNotExist(ShopSessionService.COOKIE_NAME));

        org.mockito.Mockito.verifyNoInteractions(shopSessionService);
    }

    @Test
    void result_missingSessionTokenFromFidoServer_isRejected() throws Exception {
        // fido-server 回應 verified=true 卻沒有帶 session（違反合約）；購物網站不應猜測補救。
        AuthenticationResultResponse fidoResponse = new AuthenticationResultResponse(
                true, "u-10023", "cred-abc", "dev-xyz", null);
        when(fidoServerClient.authenticationResult(any())).thenReturn(fidoResponse);

        mockMvc.perform(post("/shop/api/fido/authentication/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("MISSING_SESSION_TOKEN"));

        org.mockito.Mockito.verifyNoInteractions(shopSessionService);
        org.mockito.Mockito.verifyNoInteractions(jwtValidator);
    }
}
