package com.shop.reference.device;

import com.shop.reference.fidoclient.FidoServerApiException;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.DeviceListResponse;
import com.shop.reference.fidoclient.dto.DeviceRevokeResponse;
import com.shop.reference.session.ExternalUserIdMismatchException;
import com.shop.reference.session.NotLoggedInException;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.shop.reference.testsupport.CsrfTestSupport.csrf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 對應 docs/api-contract.md 第 4 節裝置管理代理：列表 / 撤銷（含冪等 no-op 情境）。
 *
 * <p>重點驗證安全模式：未登入一律 401；已登入時 externalUserId 一律來自 session；
 * 已登入但請求另外夾帶與 session 不同的 externalUserId 一律 403（明確拒絕，非沉默忽略）。
 */
@WebMvcTest(DeviceProxyController.class)
class DeviceProxyControllerTest {

    private static final String SESSION_ID = "shopsess_test-1";
    private static final String LOGGED_IN_USER = "u-10023";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FidoServerClient fidoServerClient;

    @MockBean
    private ShopSessionService shopSessionService;

    private Cookie sessionCookie() {
        return new Cookie(ShopSessionService.COOKIE_NAME, SESSION_ID);
    }

    private void mockLoggedIn() {
        ShopSession session = new ShopSession(SESSION_ID, LOGGED_IN_USER, null, null, Instant.now());
        when(shopSessionService.requireSession(SESSION_ID)).thenReturn(session);
        when(shopSessionService.resolveExternalUserId(eq(session), isNull())).thenReturn(LOGGED_IN_USER);
        when(shopSessionService.resolveExternalUserId(eq(session), eq(LOGGED_IN_USER))).thenReturn(LOGGED_IN_USER);
        when(shopSessionService.resolveExternalUserId(eq(session), eq("other-user")))
                .thenThrow(new ExternalUserIdMismatchException(
                        "請求夾帶的 externalUserId=other-user 與登入 session 的 externalUserId=" + LOGGED_IN_USER + " 不一致。"));
    }

    private void mockNotLoggedIn() {
        when(shopSessionService.requireSession(isNull())).thenThrow(
                new NotLoggedInException("尚未登入購物網站，請先登入。"));
    }

    @Test
    void list_notLoggedIn_returns401() throws Exception {
        mockNotLoggedIn();

        mockMvc.perform(get("/shop/api/fido/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("NOT_LOGGED_IN"));
    }

    @Test
    void list_loggedIn_usesSessionExternalUserId() throws Exception {
        mockLoggedIn();
        DeviceListResponse response = new DeviceListResponse(LOGGED_IN_USER, List.of(
                new DeviceListResponse.DeviceSummary("dev-1", "我的手機", "Pixel 8", "Android 14",
                        "STRONG_BOX", "aaguid-1", "cred-1", "ACTIVE", "2026-07-21T08:00:03Z", "2026-07-22T09:00:00Z")
        ), null);
        when(fidoServerClient.listDevices(LOGGED_IN_USER, "ACTIVE", 50)).thenReturn(response);

        // 請求不再帶 externalUserId 參數（前端已改為只依賴登入 session）。
        mockMvc.perform(get("/shop/api/fido/devices").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$.devices[0].status").value("ACTIVE"));
    }

    @Test
    void list_loggedIn_mismatchedExternalUserId_returns403() throws Exception {
        mockLoggedIn();

        mockMvc.perform(get("/shop/api/fido/devices")
                        .cookie(sessionCookie())
                        .param("externalUserId", "other-user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_USER_ID_MISMATCH"));
    }

    @Test
    void list_unknownUser_returnsEmptyListNot404() throws Exception {
        // 對齊 api-contract.md D7 防列舉：使用者不存在時 fido-server 回 200 + 空陣列，
        // 本代理端點原封轉發，不應該把它翻譯成 404。這裡的「使用者」是登入 session 本人
        // （fido-server 端沒有這個 externalUserId 的任何裝置紀錄），並非呼叫端可指定的對象。
        mockLoggedIn();
        when(fidoServerClient.listDevices(LOGGED_IN_USER, "ACTIVE", 50))
                .thenReturn(new DeviceListResponse(LOGGED_IN_USER, List.of(), null));

        mockMvc.perform(get("/shop/api/fido/devices").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices").isEmpty());
    }

    @Test
    void revoke_notLoggedIn_returns401() throws Exception {
        mockNotLoggedIn();

        mockMvc.perform(delete("/shop/api/fido/devices/dev-1").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("NOT_LOGGED_IN"));
    }

    @Test
    void revoke_loggedIn_success_returnsRevokedStatus() throws Exception {
        mockLoggedIn();
        when(fidoServerClient.revokeDevice(LOGGED_IN_USER, "dev-1"))
                .thenReturn(new DeviceRevokeResponse("dev-1", "REVOKED", "2026-07-23T10:00:00Z"));

        mockMvc.perform(delete("/shop/api/fido/devices/dev-1").with(csrf()).cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void revoke_loggedIn_mismatchedExternalUserId_returns403() throws Exception {
        mockLoggedIn();

        mockMvc.perform(delete("/shop/api/fido/devices/dev-1")
                        .with(csrf())
                        .cookie(sessionCookie())
                        .param("externalUserId", "other-user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_USER_ID_MISMATCH"));
    }

    @Test
    void revoke_fidoServerRateLimited_propagatesAs429() throws Exception {
        mockLoggedIn();
        when(fidoServerClient.revokeDevice(LOGGED_IN_USER, "dev-1")).thenThrow(new FidoServerApiException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Rate limit exceeded for this tenant.", "req-trace-9", java.util.Map.of()));

        mockMvc.perform(delete("/shop/api/fido/devices/dev-1").with(csrf()).cookie(sessionCookie()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
