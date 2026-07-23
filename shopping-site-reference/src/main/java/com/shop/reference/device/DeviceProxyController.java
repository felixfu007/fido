package com.shop.reference.device;

import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.DeviceListResponse;
import com.shop.reference.fidoclient.dto.DeviceRevokeResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 購物網站「帳號設定 / 裝置管理」頁面代理，對應 docs/api-contract.md 第 4 節。
 *
 * <p><b>安全注意（開放問題，見本次任務回報第 6 點）</b>：docs/api-contract.md 的裝置管理
 * 端點僅以 {@code X-API-Key}（租戶層級）做認證，端點本身不會檢查「發出這次 HTTP 呼叫的
 * 是不是 {@code externalUserId} 本人」——這件事完全交由呼叫端（購物網站後端）自行把關。
 * 這裡的 {@code externalUserId} 直接取自 query 參數只是為了讓示範頁面單純；正式串接
 * 「一定要」比照 {@link com.shop.reference.registration.RegistrationProxyController}
 * 的做法，改成從購物網站自己已驗證的登入 session 取得 {@code externalUserId}，並且
 * 「忽略/拒絕」任何呼叫端嘗試傳入的、與目前登入者不同的 externalUserId ——否則任何登入
 * 使用者都能列出或撤銷「別人」的 FIDO 裝置（IDOR）。
 */
@RestController
@RequestMapping("/shop/api/fido/devices")
public class DeviceProxyController {

    private final FidoServerClient fidoServerClient;

    public DeviceProxyController(FidoServerClient fidoServerClient) {
        this.fidoServerClient = fidoServerClient;
    }

    @GetMapping
    public DeviceListResponse list(@RequestParam String externalUserId,
                                    @RequestParam(defaultValue = "ACTIVE") String status,
                                    @RequestParam(defaultValue = "50") int limit) {
        return fidoServerClient.listDevices(externalUserId, status, limit);
    }

    @DeleteMapping("/{deviceId}")
    public DeviceRevokeResponse revoke(@RequestParam String externalUserId, @PathVariable String deviceId) {
        return fidoServerClient.revokeDevice(externalUserId, deviceId);
    }
}
