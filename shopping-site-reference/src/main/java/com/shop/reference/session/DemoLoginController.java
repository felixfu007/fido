package com.shop.reference.session;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * <b>示範用「假登入」端點 —— 不是真正的帳密系統，正式串接必須整套換掉。</b>
 *
 * <p>對齊 CLAUDE.md「身分權威來源是購物網站既有帳密系統，FIDO 為加掛選項」：發起 FIDO
 * 裝置註冊、查看/撤銷自己的裝置，前提都是「已經用帳密系統登入」。這個 controller 存在的
 * 唯一目的，是讓這份參考範例有一個「已驗證的購物網站 session」可以示範 ——
 * {@link #loginAs} 完全跳過帳號密碼驗證、密碼雜湊、忘記密碼等真正登入系統該做的事，
 * 只要呼叫這支 API 帶入 {@code externalUserId} 就視為「已通過帳密驗證」，直接核發
 * {@link ShopSessionService#COOKIE_NAME} session cookie。
 *
 * <p><b>正式串接時，把這整支 controller 換成你自己真正的帳密登入端點</b>
 * （驗證帳號密碼 → 建立購物網站自己的登入 session）。{@link com.shop.reference.registration.RegistrationProxyController}
 * 與 {@link com.shop.reference.device.DeviceProxyController} 不在乎登入 session 是「假登入」
 * 建立的還是「真登入」建立的 —— 它們只透過 {@link ShopSessionService#requireSession(String)}
 * 讀取 session，這才是本範例真正要示範的安全模式；假登入本身只是為了讓示範頁面可以跑起來，
 * 不是重點。
 */
@RestController
@RequestMapping("/shop/api/session")
public class DemoLoginController {

    private final ShopSessionService shopSessionService;

    /**
     * 是否對 {@link ShopSessionService#COOKIE_NAME} cookie 設 {@code Secure} 屬性。
     * checked-in 預設值（application.yml {@code shop.session.cookie.secure}）為 {@code true}
     * （正式部署全站 TLS 的正確值）；本機無 TLS 的 HTTP 開發測試須明確 opt-out，見
     * {@code application-local-http.yml}，不可倒過來把預設值改不安全。
     */
    @Value("${shop.session.cookie.secure:true}")
    private boolean secureCookie;

    public DemoLoginController(ShopSessionService shopSessionService) {
        this.shopSessionService = shopSessionService;
    }

    /** 示範假登入：帶入 externalUserId 即視為「已通過帳密驗證」，核發 SHOP_SESSION cookie。 */
    @PostMapping("/login-as")
    public DemoLoginResponse loginAs(@Valid @RequestBody DemoLoginRequest request, HttpServletResponse httpResponse) {
        ShopSession session = shopSessionService.createSession(request.externalUserId(), null, null);
        setSessionCookie(httpResponse, session.sessionId());
        return new DemoLoginResponse(true, session.externalUserId(),
                "（示範假登入）已建立購物網站 session，正式串接請改接真正的帳密系統。");
    }

    /** 示範登出：清除 session 與 cookie。 */
    @PostMapping("/logout")
    public DemoLoginResponse logout(@CookieValue(name = ShopSessionService.COOKIE_NAME, required = false) String sessionId,
                                     HttpServletResponse httpResponse) {
        shopSessionService.invalidate(sessionId);
        ResponseCookie expired = ResponseCookie.from(ShopSessionService.COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .secure(secureCookie)
                .sameSite("Lax")
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return new DemoLoginResponse(false, null, "已登出。");
    }

    /** 讓前端知道目前是否已登入、登入者是誰，用來決定要不要顯示註冊/裝置管理區塊。 */
    @GetMapping("/whoami")
    public DemoLoginResponse whoami(@CookieValue(name = ShopSessionService.COOKIE_NAME, required = false) String sessionId) {
        return shopSessionService.find(sessionId)
                .map(session -> new DemoLoginResponse(true, session.externalUserId(), "已登入。"))
                .orElseGet(() -> new DemoLoginResponse(false, null, "尚未登入。"));
    }

    private void setSessionCookie(HttpServletResponse httpResponse, String sessionId) {
        ResponseCookie cookie = ResponseCookie.from(ShopSessionService.COOKIE_NAME, sessionId)
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofMinutes(30))
                // secure 值來自 shop.session.cookie.secure（預設 true，見 application.yml 與
                // secureCookie 欄位 Javadoc），與 AuthenticationProxyController 的做法一致。
                .secure(secureCookie)
                .sameSite("Lax")
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
