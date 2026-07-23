package com.shop.reference.security;

import com.shop.reference.session.DemoLoginController;
import com.shop.reference.session.ShopSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證「本機 HTTP 開發明確 opt-out」機制（見 application-local-http.yml、CLAUDE.md 待辦事項）
 * 真的能把 {@link CsrfCookieFilter} 核發的 {@code XSRF-TOKEN} cookie 的 {@code Secure} 屬性
 * 從 checked-in 預設值 {@code true} 覆寫成 {@code false}——與
 * {@code CsrfCookieFilterTest#safeGetRequest_defaultConfig_issuesSecureXsrfTokenCookie}
 * （不覆寫、驗證預設值為 true）互為對照。
 *
 * <p>這裡直接用 {@code @TestPropertySource} 模擬 {@code --spring.profiles.active=local-http}
 * 實際套用後的效果（覆寫同一個 {@code shop.session.cookie.secure} 屬性鍵），不依賴 profile
 * 檔案本身是否被正確載入——那屬於 Spring Boot profile 機制本身的行為，不是本專案程式碼的
 * 邏輯，不需要在單元測試層級重複驗證。
 */
@WebMvcTest(DemoLoginController.class)
@TestPropertySource(properties = "shop.session.cookie.secure=false")
class CsrfCookieFilterLocalHttpOverrideTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShopSessionService shopSessionService;

    @Test
    void safeGetRequest_withLocalHttpOverride_issuesNonSecureXsrfTokenCookie() throws Exception {
        when(shopSessionService.find(isNull())).thenReturn(Optional.empty());

        mockMvc.perform(get("/shop/api/session/whoami"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure(CsrfCookieFilter.COOKIE_NAME, false));
    }
}
