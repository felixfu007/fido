package com.shop.reference.crossdevice;

import com.shop.reference.authentication.ShopLoginFinalizer;
import com.shop.reference.authentication.jwt.FidoSessionJwtValidator;
import com.shop.reference.authentication.jwt.ValidatedFidoSession;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.CrossDeviceStartRequest;
import com.shop.reference.fidoclient.dto.CrossDeviceStartResponse;
import com.shop.reference.fidoclient.dto.CrossDeviceStatusResponse;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.shop.reference.testsupport.CsrfTestSupport.csrf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 對應 CLAUDE.md「桌機瀏覽器 QR 掃碼跨裝置登入（情境三）」任務要求，涵蓋：
 * <ul>
 *   <li>{@code /start} 需要 CSRF token；</li>
 *   <li>{@code /start} 設下的 {@code XDEV_POLL} cookie 正確綁定 fido-server 回傳的
 *       {@code xdevId}（透過 {@link CrossDevicePollSessionService} 對映，不外洩給前端）；</li>
 *   <li>{@code /poll} 只認 cookie，用查詢參數帶 {@code xdevId} 完全無效（負向測試）；</li>
 *   <li>{@code /poll} 對 {@code PENDING}/{@code SCANNED}/{@code DENIED}/{@code EXPIRED} 各狀態
 *       的正確回應；</li>
 *   <li>{@code CONFIRMED} 時的收尾與 {@code AuthenticationProxyControllerTest} 的
 *       {@code /result} 收尾一樣，都是透過 {@link ShopLoginFinalizer} 建立
 *       {@code SHOP_SESSION}（同一份實作，此處用 {@code @Import(ShopLoginFinalizer.class)}
 *       載入真正的 finalizer，驗證兩處收尾行為一致，而不是各自 mock 一份）。</li>
 * </ul>
 */
@WebMvcTest(CrossDeviceAuthenticationProxyController.class)
@Import({ShopLoginFinalizer.class, CrossDevicePollSessionService.class})
class CrossDeviceAuthenticationProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FidoServerClient fidoServerClient;

    @MockBean
    private FidoSessionJwtValidator jwtValidator;

    @MockBean
    private ShopSessionService shopSessionService;

    // ---------------------------------------------------------------------
    // /start
    // ---------------------------------------------------------------------

    @Test
    void start_withoutCsrfToken_isRejected() throws Exception {
        mockMvc.perform(post("/shop/api/fido/authentication/cross-device/start"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.source").value("SHOP_CSRF"))
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_MISSING"));

        org.mockito.Mockito.verifyNoInteractions(fidoServerClient);
    }

    @Test
    void start_withCsrfToken_callsFidoServerAndReturnsQrPayloadAndSetsPollCookie() throws Exception {
        when(fidoServerClient.crossDeviceStart(any())).thenReturn(
                new CrossDeviceStartResponse("xdev-secret-abc", "https://fido-app-link.example/x/xdev-secret-abc",
                        "38-421", 120));

        mockMvc.perform(post("/shop/api/fido/authentication/cross-device/start").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrUrl").value("https://fido-app-link.example/x/xdev-secret-abc"))
                .andExpect(jsonPath("$.verificationCode").value("38-421"))
                .andExpect(jsonPath("$.expiresIn").value(120))
                // xdevId 本身不應該出現在回應 body 裡（能力憑證，只透過 httpOnly cookie 傳遞）。
                .andExpect(jsonPath("$.xdevId").doesNotExist())
                .andExpect(cookie().exists(CrossDeviceAuthenticationProxyController.POLL_COOKIE_NAME))
                .andExpect(cookie().httpOnly(CrossDeviceAuthenticationProxyController.POLL_COOKIE_NAME, true))
                .andExpect(cookie().secure(CrossDeviceAuthenticationProxyController.POLL_COOKIE_NAME, true));

        verify(fidoServerClient).crossDeviceStart(any());
    }

    @Test
    void start_forwardsDesktopClientIpFromXForwardedFor() throws Exception {
        when(fidoServerClient.crossDeviceStart(any())).thenReturn(
                new CrossDeviceStartResponse("xdev-1", "https://fido-app-link.example/x/xdev-1", "11-111", 120));

        mockMvc.perform(post("/shop/api/fido/authentication/cross-device/start")
                        .with(csrf())
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(status().isOk());

        verify(fidoServerClient).crossDeviceStart(eq(new CrossDeviceStartRequest("203.0.113.9")));
    }

    // ---------------------------------------------------------------------
    // /poll — cookie 綁定與查詢參數負向測試
    // ---------------------------------------------------------------------

    @Test
    void poll_withoutPollCookie_isRejected() throws Exception {
        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XDEV_POLL_SESSION_NOT_FOUND"));

        org.mockito.Mockito.verifyNoInteractions(fidoServerClient);
    }

    /**
     * 核心安全負向測試：即使呼叫端在查詢參數夾帶一個「本來會成功」的 xdevId，poll 端點也完全不
     * 讀取它——本 controller 的方法簽章根本沒有宣告 xdevId 請求參數，唯一輸入是
     * {@code XDEV_POLL} cookie。故意不帶 cookie、只帶 query 參數，驗證一樣被拒絕，證明「拿到
     * QR 或猜到 xdevId 的第三方」無法繞過 cookie 冒領結果。
     */
    @Test
    void poll_withXdevIdQueryParamButNoPollCookie_isRejected_queryParamIsIgnored() throws Exception {
        // 先合法建立一組 binding，證明就算攻擊者猜對了「真正有效」的 xdevId 字面值，
        // 用 query string 帶它一樣沒用。
        when(fidoServerClient.crossDeviceStart(any())).thenReturn(
                new CrossDeviceStartResponse("xdev-real-session", "https://fido-app-link.example/x/xdev-real-session",
                        "22-222", 120));
        mockMvc.perform(post("/shop/api/fido/authentication/cross-device/start").with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll")
                        .param("xdevId", "xdev-real-session"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XDEV_POLL_SESSION_NOT_FOUND"));

        // fido-server 的 status 端點完全沒被打到——poll 從未真的用上那個 xdevId。
        org.mockito.Mockito.verify(fidoServerClient, never()).crossDeviceStatus(anyString(), anyString());
    }

    @Test
    void poll_withUnknownPollCookieValue_isRejected() throws Exception {
        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll")
                        .cookie(new Cookie(CrossDeviceAuthenticationProxyController.POLL_COOKIE_NAME, "not-a-real-poll-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XDEV_POLL_SESSION_NOT_FOUND"));
    }

    // ---------------------------------------------------------------------
    // /poll — 各狀態回應
    // ---------------------------------------------------------------------

    private Cookie startAndGetPollCookie(String xdevId) throws Exception {
        when(fidoServerClient.crossDeviceStart(any())).thenReturn(
                new CrossDeviceStartResponse(xdevId, "https://fido-app-link.example/x/" + xdevId, "33-333", 120));

        var result = mockMvc.perform(post("/shop/api/fido/authentication/cross-device/start").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie pollCookie = result.getResponse().getCookie(CrossDeviceAuthenticationProxyController.POLL_COOKIE_NAME);
        org.assertj.core.api.Assertions.assertThat(pollCookie).isNotNull();
        return pollCookie;
    }

    @Test
    void poll_pending_returnsPendingWithoutTouchingSession() throws Exception {
        Cookie pollCookie = startAndGetPollCookie("xdev-pending");
        when(fidoServerClient.crossDeviceStatus("xdev-pending", "127.0.0.1"))
                .thenReturn(new CrossDeviceStatusResponse("PENDING", null, null));

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        org.mockito.Mockito.verifyNoInteractions(shopSessionService);

        // 對映尚未失效：再 poll 一次仍應成功打到 fido-server（非一次性終態）。
        when(fidoServerClient.crossDeviceStatus("xdev-pending", "127.0.0.1"))
                .thenReturn(new CrossDeviceStatusResponse("SCANNED", null, null));
        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"));
    }

    @Test
    void poll_denied_returnsDeniedAndInvalidatesPollSession() throws Exception {
        Cookie pollCookie = startAndGetPollCookie("xdev-denied");
        when(fidoServerClient.crossDeviceStatus("xdev-denied", "127.0.0.1"))
                .thenReturn(new CrossDeviceStatusResponse("DENIED", null, null));

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));

        // 終態已達成，poll secret 應立即失效：同一顆 cookie 再 poll 一次應變成查無 session。
        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XDEV_POLL_SESSION_NOT_FOUND"));
    }

    @Test
    void poll_expired_returnsExpiredAndInvalidatesPollSession() throws Exception {
        Cookie pollCookie = startAndGetPollCookie("xdev-expired");
        when(fidoServerClient.crossDeviceStatus("xdev-expired", "127.0.0.1"))
                .thenReturn(new CrossDeviceStatusResponse("EXPIRED", null, null));

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------
    // /poll — CONFIRMED 收尾：與 /result 一致（只信驗過簽的 JWT），並標記 crossDeviceLogin
    // ---------------------------------------------------------------------

    @Test
    void poll_confirmed_validatesJwtAndCreatesShopSessionMarkedAsCrossDevice() throws Exception {
        Cookie pollCookie = startAndGetPollCookie("xdev-confirmed");
        when(fidoServerClient.crossDeviceStatus("xdev-confirmed", "127.0.0.1")).thenReturn(
                new CrossDeviceStatusResponse("CONFIRMED",
                        new CrossDeviceStatusResponse.SessionInfo("header.payload.signature", "Bearer", 120),
                        new CrossDeviceStatusResponse.Warnings(false)));

        // amr 含 "xdev"：這是 fido-server 對跨裝置登入 JWT 的標記方式（D17）。
        ValidatedFidoSession validated = new ValidatedFidoSession(
                "u-20099", "tenant-uid-1", "cred-xdev-1", "dev-xdev-1",
                List.of("fido", "hwk", "xdev"), Instant.now().getEpochSecond(), "jti-xdev-1");
        when(jwtValidator.validate("header.payload.signature")).thenReturn(validated);

        ShopSession shopSession = new ShopSession("shopsess_xdev_1", "u-20099", "dev-xdev-1", "cred-xdev-1",
                true, Instant.now());
        when(shopSessionService.createSession("u-20099", "dev-xdev-1", "cred-xdev-1", true)).thenReturn(shopSession);

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.externalUserId").value("u-20099"))
                .andExpect(cookie().exists(ShopSessionService.COOKIE_NAME))
                .andExpect(cookie().value(ShopSessionService.COOKIE_NAME, "shopsess_xdev_1"))
                .andExpect(cookie().httpOnly(ShopSessionService.COOKIE_NAME, true));

        // 關鍵斷言：createSession 被呼叫時 crossDeviceLogin=true（衍生自 JWT amr 含 "xdev"）——
        // 這正是 amr step-up 示範邏輯（DeviceProxyController#revoke）依賴的標記來源。
        verify(shopSessionService).createSession("u-20099", "dev-xdev-1", "cred-xdev-1", true);

        // 一次性：CONFIRMED 已消費，同一顆 poll cookie 再 poll 應查無 session。
        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XDEV_POLL_SESSION_NOT_FOUND"));

        verify(fidoServerClient, times(1)).crossDeviceStatus("xdev-confirmed", "127.0.0.1");
    }

    @Test
    void poll_confirmed_missingSessionToken_isRejectedWithoutCreatingSession() throws Exception {
        Cookie pollCookie = startAndGetPollCookie("xdev-broken");
        when(fidoServerClient.crossDeviceStatus("xdev-broken", "127.0.0.1"))
                .thenReturn(new CrossDeviceStatusResponse("CONFIRMED", null, null));

        mockMvc.perform(get("/shop/api/fido/authentication/cross-device/poll").cookie(pollCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("MISSING_SESSION_TOKEN"));

        org.mockito.Mockito.verifyNoInteractions(shopSessionService);
    }
}
