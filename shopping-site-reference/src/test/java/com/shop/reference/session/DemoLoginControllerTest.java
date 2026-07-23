package com.shop.reference.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DemoLoginController} 是示範用假登入端點（見該類別 Javadoc）；這裡只驗證它符合本身
 * 該做的事：核發/清除 {@link ShopSessionService#COOKIE_NAME} cookie、{@code whoami} 正確反映
 * 目前是否已登入 —— 不驗證任何帳密邏輯（本來就不存在）。
 */
@WebMvcTest(DemoLoginController.class)
class DemoLoginControllerTest {

    private static final String SESSION_ID = "shopsess_test-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ShopSessionService shopSessionService;

    @Test
    void loginAs_createsSessionAndSetsCookie() throws Exception {
        ShopSession session = new ShopSession(SESSION_ID, "u-10023", null, null, Instant.now());
        when(shopSessionService.createSession("u-10023", null, null)).thenReturn(session);

        mockMvc.perform(post("/shop/api/session/login-as")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "u-10023" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(true))
                .andExpect(jsonPath("$.externalUserId").value("u-10023"))
                .andExpect(cookie().value(ShopSessionService.COOKIE_NAME, SESSION_ID))
                .andExpect(cookie().httpOnly(ShopSessionService.COOKIE_NAME, true));
    }

    @Test
    void loginAs_missingExternalUserId_returns400() throws Exception {
        mockMvc.perform(post("/shop/api/session/login-as")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void whoami_noCookie_returnsNotLoggedIn() throws Exception {
        when(shopSessionService.find(isNull())).thenReturn(Optional.empty());

        mockMvc.perform(get("/shop/api/session/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(false))
                .andExpect(jsonPath("$.externalUserId").doesNotExist());
    }

    @Test
    void whoami_withValidCookie_returnsLoggedInUser() throws Exception {
        ShopSession session = new ShopSession(SESSION_ID, "u-10023", null, null, Instant.now());
        when(shopSessionService.find(SESSION_ID)).thenReturn(Optional.of(session));

        mockMvc.perform(get("/shop/api/session/whoami").cookie(new Cookie(ShopSessionService.COOKIE_NAME, SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(true))
                .andExpect(jsonPath("$.externalUserId").value("u-10023"));
    }

    @Test
    void logout_invalidatesSessionAndExpiresCookie() throws Exception {
        mockMvc.perform(post("/shop/api/session/logout")
                        .cookie(new Cookie(ShopSessionService.COOKIE_NAME, SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(false))
                .andExpect(cookie().maxAge(ShopSessionService.COOKIE_NAME, 0));

        verify(shopSessionService).invalidate(SESSION_ID);
    }
}
