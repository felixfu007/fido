package com.shop.reference.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CSRF 防護：double-submit cookie pattern。
 *
 * <p><b>為什麼手寫而不是引入 Spring Security</b>：本專案目前完全沒有 Spring Security 依賴——
 * 「誰是誰」這件事全靠 {@link com.shop.reference.session.ShopSessionService} 自訂的
 * {@code SHOP_SESSION} cookie session 手動把關（見各 controller 的
 * {@code requireSession}/{@code resolveExternalUserId} 呼叫），完全不是 Spring Security
 * 的 {@code Authentication}/{@code SecurityContext} 模型。若為了「加一個 CSRF filter」而引入
 * {@code spring-boot-starter-security}，會連帶拉入一整套與本專案身分模型無關的自動配置
 * （預設要求所有端點通過 {@code SecurityFilterChain} 授權、預設登入頁、httpBasic 等），
 * 之後還得逐一 {@code permitAll()} 現有端點、確認不會與既有的 session 邏輯打架——這些配置
 * 複雜度與本範例真正要示範的 FIDO 串接模式無關，對一份「照抄用」的參考範例反而是雜訊。
 * 手寫的 double-submit cookie filter 只有一個檔案、邏輯完全攤在陽光下，符合本專案一貫的
 * 「串接邏輯完全攤開，方便照抄」設計取向（見 {@code app.js} 開頭註解）。
 *
 * <p><b>做法</b>：任何請求若沒有 {@value #COOKIE_NAME} cookie，就核發一個新的隨機 token 作為
 * cookie（{@code HttpOnly=false}，前端 JS 需要讀得到才能回填進 header——這是
 * double-submit cookie pattern 的必要條件，與 {@code SHOP_SESSION} 那個必須
 * {@code HttpOnly=true} 的登入 session cookie 目的不同，不要混淆）。對於「不安全」方法
 * （POST/PUT/PATCH/DELETE，GET/HEAD/OPTIONS/TRACE 視為安全方法不強制）的請求，要求
 * request header {@value #HEADER_NAME} 存在且與 cookie 值相符，否則一律拒絕（403）。
 *
 * <p>攻擊者網站可以誘導受害者瀏覽器發出帶 cookie 的跨站請求（瀏覽器會自動夾帶
 * {@code SHOP_SESSION} cookie），但讀不到受害者瀏覽器裡 {@value #COOKIE_NAME} cookie 的值
 * （同源政策），因此組不出正確的 {@value #HEADER_NAME} header——這正是這個防護生效的原理。
 *
 * <p>Cookie 的 {@code Secure} 屬性沿用與 {@code SHOP_SESSION} 相同的
 * {@code shop.session.cookie.secure} 設定（見 application.yml 註解、
 * {@link com.shop.reference.session.DemoLoginController}、
 * {@link com.shop.reference.authentication.AuthenticationProxyController}）——概念上是同一個
 * 「這個環境有沒有跑在 TLS 之後」的判斷，讓兩個 cookie 只需要一個開關就能同步調整，
 * 不會有人只改了其中一個、另一個忘記同步而在本機 HTTP 環境下悄悄失效。
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ObjectMapper objectMapper;

    @Value("${shop.session.cookie.secure:true}")
    private boolean secureCookie;

    public CsrfCookieFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String cookieToken = readCookie(request);
        boolean cookieWasMissing = cookieToken == null || cookieToken.isBlank();
        if (cookieWasMissing) {
            cookieToken = generateToken();
            issueCookie(response, cookieToken);
        }

        if (!SAFE_METHODS.contains(request.getMethod())) {
            String headerToken = request.getHeader(HEADER_NAME);
            if (headerToken == null || headerToken.isBlank()) {
                rejectWithCsrfError(response, "CSRF_TOKEN_MISSING",
                        "缺少 " + HEADER_NAME + " header，拒絕這次狀態變更請求（CSRF 防護）。");
                return;
            }
            if (cookieWasMissing || !constantTimeEquals(headerToken, cookieToken)) {
                rejectWithCsrfError(response, "CSRF_TOKEN_MISMATCH",
                        HEADER_NAME + " header 與 " + COOKIE_NAME + " cookie 不一致，拒絕這次狀態變更請求"
                                + "（CSRF 防護，double-submit cookie pattern）。");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void issueCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                // 刻意 httpOnly=false：前端 JS 必須讀得到這個 cookie 值，才能回填進
                // X-XSRF-TOKEN header——這是 double-submit cookie pattern 的必要條件。
                .httpOnly(false)
                .path("/")
                .secure(secureCookie)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void rejectWithCsrfError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("source", "SHOP_CSRF");
        errorBody.put("code", code);
        errorBody.put("message", message);
        errorBody.put("details", Map.of());

        objectMapper.writeValue(response.getWriter(), Map.of("error", errorBody));
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
