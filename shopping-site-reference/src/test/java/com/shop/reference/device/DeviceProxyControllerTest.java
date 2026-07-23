package com.shop.reference.device;

import com.shop.reference.fidoclient.FidoServerApiException;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.DeviceListResponse;
import com.shop.reference.fidoclient.dto.DeviceRevokeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 對應 docs/api-contract.md 第 4 節裝置管理代理：列表 / 撤銷（含冪等 no-op 情境）。 */
@WebMvcTest(DeviceProxyController.class)
class DeviceProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FidoServerClient fidoServerClient;

    @Test
    void list_forwardsAndReturnsDevices() throws Exception {
        DeviceListResponse response = new DeviceListResponse("u-10023", List.of(
                new DeviceListResponse.DeviceSummary("dev-1", "我的手機", "Pixel 8", "Android 14",
                        "STRONG_BOX", "aaguid-1", "cred-1", "ACTIVE", "2026-07-21T08:00:03Z", "2026-07-22T09:00:00Z")
        ), null);
        when(fidoServerClient.listDevices("u-10023", "ACTIVE", 50)).thenReturn(response);

        mockMvc.perform(get("/shop/api/fido/devices").param("externalUserId", "u-10023"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$.devices[0].status").value("ACTIVE"));
    }

    @Test
    void list_unknownUser_returnsEmptyListNot404() throws Exception {
        // 對齊 api-contract.md D7 防列舉：使用者不存在時 fido-server 回 200 + 空陣列，
        // 本代理端點原封轉發，不應該把它翻譯成 404。
        when(fidoServerClient.listDevices("no-such-user", "ACTIVE", 50))
                .thenReturn(new DeviceListResponse("no-such-user", List.of(), null));

        mockMvc.perform(get("/shop/api/fido/devices").param("externalUserId", "no-such-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices").isEmpty());
    }

    @Test
    void revoke_success_returnsRevokedStatus() throws Exception {
        when(fidoServerClient.revokeDevice("u-10023", "dev-1"))
                .thenReturn(new DeviceRevokeResponse("dev-1", "REVOKED", "2026-07-23T10:00:00Z"));

        mockMvc.perform(delete("/shop/api/fido/devices/dev-1").param("externalUserId", "u-10023"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void revoke_fidoServerRateLimited_propagatesAs429() throws Exception {
        when(fidoServerClient.revokeDevice("u-10023", "dev-1")).thenThrow(new FidoServerApiException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Rate limit exceeded for this tenant.", "req-trace-9", java.util.Map.of()));

        mockMvc.perform(delete("/shop/api/fido/devices/dev-1").param("externalUserId", "u-10023"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
