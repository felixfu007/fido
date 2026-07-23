package com.shop.reference.security;

import com.shop.reference.session.DemoLoginController;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CsrfCookieFilter} 的獨立行為驗證（double-submit cookie pattern），借用
 * {@link DemoLoginController} 的端點作為載體（{@code @WebMvcTest} 會自動把 filter bean
 * 掛進切片，見 {@link com.shop.reference.testsupport.CsrfTestSupport} Javadoc），與
 * {@code DemoLoginControllerTest} 分開——那邊只關心登入邏輯本身（已改用
 * {@code .with(CsrfTestSupport.csrf())} 補齊前置條件），這裡才是 CSRF 防護本身的測試。
 */
@WebMvcTest(DemoLoginController.class)
class CsrfCookieFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShopSessionService shopSessionService;

    @Test
    void unsafeRequest_withoutCsrfCookieOrHeader_isRejectedWithMissingCode() throws Exception {
        mockMvc.perform(post("/shop/api/session/login-as")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "u-10023" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.source").value("SHOP_CSRF"))
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_MISSING"));
    }

    @Test
    void unsafeRequest_withCookieButNoHeader_isRejectedWithMissingCode() throws Exception {
        mockMvc.perform(post("/shop/api/session/login-as")
                        .cookie(new Cookie(CsrfCookieFilter.COOKIE_NAME, "some-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "u-10023" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_MISSING"));
    }

    @Test
    void unsafeRequest_withMismatchedHeaderAndCookie_isRejectedWithMismatchCode() throws Exception {
        mockMvc.perform(post("/shop/api/session/login-as")
                        .cookie(new Cookie(CsrfCookieFilter.COOKIE_NAME, "cookie-value"))
                        .header(CsrfCookieFilter.HEADER_NAME, "different-header-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "u-10023" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.source").value("SHOP_CSRF"))
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_MISMATCH"));
    }

    @Test
    void unsafeRequest_withMatchingCookieAndHeader_isAllowedThrough() throws Exception {
        ShopSession session = new ShopSession("shopsess_1", "u-10023", null, null, Instant.now());
        when(shopSessionService.createSession(eq("u-10023"), isNull(), isNull())).thenReturn(session);

        mockMvc.perform(post("/shop/api/session/login-as")
                        .cookie(new Cookie(CsrfCookieFilter.COOKIE_NAME, "matching-token"))
                        .header(CsrfCookieFilter.HEADER_NAME, "matching-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "externalUserId": "u-10023" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(true));
    }

    @Test
    void safeGetRequest_withoutExistingCookie_issuesXsrfTokenCookie() throws Exception {
        when(shopSessionService.find(isNull())).thenReturn(Optional.empty());

        mockMvc.perform(get("/shop/api/session/whoami"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(CsrfCookieFilter.COOKIE_NAME))
                .andExpect(cookie().httpOnly(CsrfCookieFilter.COOKIE_NAME, false));
    }

    @Test
    void safeGetRequest_doesNotRequireCsrfTokenAtAll() throws Exception {
        when(shopSessionService.find(isNull())).thenReturn(Optional.empty());

        // GET 是安全方法，即使完全沒有帶任何 CSRF cookie/header 也應該直接放行（與上面
        // unsafeRequest_withoutCsrfCookieOrHeader_isRejectedWithMissingCode 的 POST 情境對比）。
        mockMvc.perform(get("/shop/api/session/whoami"))
                .andExpect(status().isOk());
    }

    @Test
    void safeGetRequest_defaultConfig_issuesSecureXsrfTokenCookie() throws Exception {
        // 沒有任何 @TestPropertySource 覆寫時，application.yml 的 checked-in 預設值
        // （shop.session.cookie.secure=true）應該生效，XSRF-TOKEN 與 SHOP_SESSION 用同一個
        // 開關（見 CsrfCookieFilter Javadoc），覆寫情境見同套件內
        // CsrfCookieFilterLocalHttpOverrideTest。
        when(shopSessionService.find(isNull())).thenReturn(Optional.empty());

        mockMvc.perform(get("/shop/api/session/whoami"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure(CsrfCookieFilter.COOKIE_NAME, true));
    }
}
