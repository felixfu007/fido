package com.shop.reference.testsupport;

import com.shop.reference.security.CsrfCookieFilter;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

/**
 * 測試輔助工具：幫 {@code MockMvc} 請求補上一組「合法配對」的 CSRF cookie + header
 * （{@link CsrfCookieFilter} 的 double-submit cookie pattern），命名/用法刻意模仿
 * Spring Security Test 的 {@code SecurityMockMvcRequestPostProcessors.csrf()}——雖然本專案
 * 沒有依賴 Spring Security（見 {@link CsrfCookieFilter} Javadoc 的取捨說明），但這是同一種
 * 「用 RequestPostProcessor 補齊 CSRF 前置條件」慣用寫法。
 *
 * <p>{@link CsrfCookieFilter} 是掛在所有請求上的 Servlet {@code Filter}，{@code @WebMvcTest}
 * 切片會自動掃到它（{@code @WebMvcTest} 的允許清單包含 {@code Filter} bean），因此任何測試
 * 對「不安全方法」（POST/PUT/PATCH/DELETE）端點送出的請求，如果沒有補上這組 cookie/header，
 * 一律會先被這個 filter 擋下 403，不會進到 controller 邏輯——各 controller 測試類別對這類
 * 請求都需要用 {@code .with(CsrfTestSupport.csrf())} 補上。
 */
public final class CsrfTestSupport {

    /** 測試用固定 token 值，只要 cookie 與 header 用同一個值即符合 double-submit 驗證。 */
    public static final String TOKEN = "test-csrf-token-0123456789abcdef";

    private CsrfTestSupport() {
    }

    public static RequestPostProcessor csrf() {
        return request -> {
            Cookie[] existing = request.getCookies();
            Cookie[] updated = Arrays.copyOf(existing == null ? new Cookie[0] : existing,
                    (existing == null ? 0 : existing.length) + 1);
            updated[updated.length - 1] = new Cookie(CsrfCookieFilter.COOKIE_NAME, TOKEN);
            request.setCookies(updated);
            request.addHeader(CsrfCookieFilter.HEADER_NAME, TOKEN);
            return request;
        };
    }
}
