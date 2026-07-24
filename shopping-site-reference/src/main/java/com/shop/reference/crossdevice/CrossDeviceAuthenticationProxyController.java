package com.shop.reference.crossdevice;

import com.shop.reference.authentication.ShopLoginFinalizer;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.CrossDeviceStartRequest;
import com.shop.reference.fidoclient.dto.CrossDeviceStartResponse;
import com.shop.reference.fidoclient.dto.CrossDeviceStatusResponse;
import com.shop.reference.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 購物網站「桌機瀏覽器 QR 掃碼跨裝置登入」流程代理（情境三），對應
 * docs/api-contract.md §3.4 端點 A（建立 session）與 D（輪詢狀態 / 取 JWT）。完整設計見
 * docs/decisions/qr-cross-device-login-design.md 第 6.2–6.4 節。
 *
 * <p>手機 App 直連 fido-server 端點 B（claim）/C（result），本 controller 不經手、也不參與
 * 那兩個端點（見 docs/api-contract.md §1.2.2 / D16：手機 App 以 {@code xdevId} capability
 * 認證、不帶 X-API-Key，與購物網站後端是完全不同的呼叫路徑）。
 *
 * <p><b>{@code XDEV_POLL} cookie 的安全性質（設計文件 6.4 節）</b>：{@link #start} 收到
 * fido-server 回傳的 {@code xdevId} 後，不會把它原封回傳給前端 JS，而是透過
 * {@link CrossDevicePollSessionService} 建立一組「poll secret → xdevId」的伺服器端對映，只把
 * poll secret 放進 httpOnly 的 {@value #POLL_COOKIE_NAME} cookie。{@link #poll} 因此
 * <b>只認這個 cookie</b>——本方法簽章刻意不接受任何 {@code xdevId} 請求參數，就算呼叫端在
 * query string 夾帶一個看似有效的 {@code xdevId} 也不會被讀取，確保「拿到 QR 或猜到
 * {@code xdevId} 的第三方」無法冒領別人的登入結果。
 */
@RestController
@RequestMapping("/shop/api/fido/authentication/cross-device")
public class CrossDeviceAuthenticationProxyController {

    private static final Logger log = LoggerFactory.getLogger(CrossDeviceAuthenticationProxyController.class);

    public static final String POLL_COOKIE_NAME = "XDEV_POLL";

    private final FidoServerClient fidoServerClient;
    private final CrossDevicePollSessionService pollSessionService;
    private final ShopLoginFinalizer shopLoginFinalizer;

    /** 沿用與 {@code SHOP_SESSION}/{@code XSRF-TOKEN} 相同的 {@code shop.session.cookie.secure} 開關（預設 {@code true}）。 */
    @Value("${shop.session.cookie.secure:true}")
    private boolean secureCookie;

    public CrossDeviceAuthenticationProxyController(FidoServerClient fidoServerClient,
                                                      CrossDevicePollSessionService pollSessionService,
                                                      ShopLoginFinalizer shopLoginFinalizer) {
        this.fidoServerClient = fidoServerClient;
        this.pollSessionService = pollSessionService;
        this.shopLoginFinalizer = shopLoginFinalizer;
    }

    /**
     * 對應 docs/api-contract.md §3.4.A。需 CSRF token（{@link com.shop.reference.security.CsrfCookieFilter}
     * 對所有「不安全方法」全域套用，此端點不例外）。
     */
    @PostMapping("/start")
    public CrossDeviceStartResponseDto start(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String desktopClientIp = ClientIpResolver.resolve(httpRequest);

        CrossDeviceStartResponse fidoResponse = fidoServerClient.crossDeviceStart(
                new CrossDeviceStartRequest(desktopClientIp));

        String pollSecret = pollSessionService.createBinding(fidoResponse.xdevId());
        issuePollCookie(httpResponse, pollSecret);

        return new CrossDeviceStartResponseDto(
                fidoResponse.qrUrl(), fidoResponse.verificationCode(), fidoResponse.expiresIn());
    }

    /**
     * 對應 docs/api-contract.md §3.4.D。GET 屬安全方法，不受 CSRF filter 強制檢查（見
     * {@link com.shop.reference.security.CsrfCookieFilter#doFilterInternal}）。
     *
     * <p>{@code CONFIRMED} 時的收尾邏輯（驗 JWT → 建 {@code SHOP_SESSION} → 設 cookie）
     * 與同裝置 {@link com.shop.reference.authentication.AuthenticationProxyController#result}
     * 呼叫的是完全同一份 {@link ShopLoginFinalizer#finalize}，兩處收尾行為保證一致。
     */
    @GetMapping("/poll")
    public CrossDevicePollResponseDto poll(
            @CookieValue(name = POLL_COOKIE_NAME, required = false) String pollSecret,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        String xdevId = pollSessionService.resolveXdevId(pollSecret)
                .orElseThrow(() -> new CrossDevicePollSessionNotFoundException(
                        "缺少有效的 " + POLL_COOKIE_NAME + " cookie，無法查詢跨裝置登入狀態"
                                + "（本端點不接受用查詢參數帶 xdevId）。"));

        String desktopClientIp = ClientIpResolver.resolve(httpRequest);
        CrossDeviceStatusResponse fidoResponse = fidoServerClient.crossDeviceStatus(xdevId, desktopClientIp);

        return switch (fidoResponse.status()) {
            case "PENDING", "SCANNED" -> CrossDevicePollResponseDto.waiting(fidoResponse.status());
            case "CONFIRMED" -> {
                // 一次性：JWT 只能被領一次，領到（不論成功或失敗）後這組 poll secret 就不該再用。
                pollSessionService.invalidate(pollSecret);

                if (fidoResponse.warnings() != null && fidoResponse.warnings().proximityMismatch()) {
                    log.warn("跨裝置登入 proximity 檢查不一致（xdevId 對應 session 已由 fido-server 標記警示，"
                            + "S2 政策為只警示不阻擋，登入仍可能成功）。");
                }

                String token = fidoResponse.session() == null ? null : fidoResponse.session().token();
                ShopLoginFinalizer.FinalizedLogin finalized = shopLoginFinalizer.finalize(token, null, httpResponse);
                yield CrossDevicePollResponseDto.confirmed(finalized.validated().externalUserId());
            }
            case "DENIED" -> {
                pollSessionService.invalidate(pollSecret);
                yield CrossDevicePollResponseDto.denied();
            }
            case "EXPIRED" -> {
                pollSessionService.invalidate(pollSecret);
                yield CrossDevicePollResponseDto.expired();
            }
            default -> throw new IllegalStateException(
                    "fido-server 回傳未知的跨裝置登入狀態：" + fidoResponse.status());
        };
    }

    private void issuePollCookie(HttpServletResponse httpResponse, String pollSecret) {
        ResponseCookie cookie = ResponseCookie.from(POLL_COOKIE_NAME, pollSecret)
                .httpOnly(true)
                .path("/")
                // xdev session TTL 為 120 秒（S6），poll cookie 存活時間對齊即可，逾時前端本就該
                // 停止輪詢/重新產生 QR，cookie 本身多留一點寬限不影響安全性（對映本身仍以
                // CONFIRMED/DENIED/EXPIRED 任一終態立即失效為準，不依賴 cookie maxAge）。
                .maxAge(Duration.ofSeconds(150))
                .secure(secureCookie)
                .sameSite("Lax")
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
