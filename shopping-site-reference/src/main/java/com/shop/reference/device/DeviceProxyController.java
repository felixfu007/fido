package com.shop.reference.device;

import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.DeviceListResponse;
import com.shop.reference.fidoclient.dto.DeviceRevokeResponse;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import com.shop.reference.session.StepUpRequiredException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 購物網站「帳號設定 / 裝置管理」頁面代理，對應 docs/api-contract.md 第 4 節。
 *
 * <p><b>安全模式（已示範正確做法）</b>：docs/api-contract.md 的裝置管理端點僅以
 * {@code X-API-Key}（租戶層級）做認證，端點本身不會檢查「發出這次 HTTP 呼叫的
 * 是不是 externalUserId 本人」——這件事完全交由呼叫端（購物網站後端，也就是這個
 * controller）自行把關。兩個方法都不接受呼叫端指定要查/撤銷「誰」的裝置：
 * {@code externalUserId} 一律透過 {@link ShopSessionService#requireSession(String)} 從購物
 * 網站自己的登入 session 取得（沒有有效 session 直接 401）；如果請求裡另外夾帶了
 * query 參數 {@code externalUserId} 且與 session 不同，會被
 * {@link ShopSessionService#resolveExternalUserId(ShopSession, String)} 明確拒絕（403），
 * 而不是沉默地以 session 為準、忽略呼叫端傳入的值 —— 否則任何登入使用者都能列出或撤銷
 * 「別人」的 FIDO 裝置（IDOR）。
 */
@RestController
@RequestMapping("/shop/api/fido/devices")
public class DeviceProxyController {

    private final FidoServerClient fidoServerClient;
    private final ShopSessionService shopSessionService;

    public DeviceProxyController(FidoServerClient fidoServerClient, ShopSessionService shopSessionService) {
        this.fidoServerClient = fidoServerClient;
        this.shopSessionService = shopSessionService;
    }

    @GetMapping
    public DeviceListResponse list(
            @CookieValue(name = ShopSessionService.COOKIE_NAME, required = false) String sessionId,
            @RequestParam(required = false) String externalUserId,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "50") int limit) {
        ShopSession session = shopSessionService.requireSession(sessionId);
        String trustedExternalUserId = shopSessionService.resolveExternalUserId(session, externalUserId);
        return fidoServerClient.listDevices(trustedExternalUserId, status, limit);
    }

    /**
     * 撤銷裝置屬於「敏感操作」，示範 docs/api-contract.md §1.3 / D17（與
     * docs/vendor/api-integration-guide.md §11.3）要求的 {@code amr} step-up 檢查：如果目前
     * 登入 session 是經跨裝置 QR 登入（情境三）取得
     * （{@link ShopSession#crossDeviceLogin()}，來源為
     * {@link com.shop.reference.authentication.jwt.ValidatedFidoSession#isCrossDeviceLogin()}），
     * 一律拒絕、要求使用者先在同裝置重新驗證，**不可**直接放行撤銷（見
     * {@link com.shop.reference.session.StepUpRequiredException} Javadoc 說明責任邊界）。
     * 同裝置登入（{@code crossDeviceLogin=false}）則正常放行，行為與此檢查加入前完全一致。
     */
    @DeleteMapping("/{deviceId}")
    public DeviceRevokeResponse revoke(
            @CookieValue(name = ShopSessionService.COOKIE_NAME, required = false) String sessionId,
            @RequestParam(required = false) String externalUserId,
            @PathVariable String deviceId) {
        ShopSession session = shopSessionService.requireSession(sessionId);
        String trustedExternalUserId = shopSessionService.resolveExternalUserId(session, externalUserId);

        if (session.crossDeviceLogin()) {
            throw new StepUpRequiredException(
                    "此操作（撤銷裝置）需要同裝置重新驗證：目前登入 session 是透過跨裝置 QR 登入"
                            + "（防釣魚較弱路徑，見 docs/api-contract.md D17）取得，請先透過本機瀏覽器/App"
                            + "重新完成一次 FIDO 或帳密驗證後再試。");
        }

        return fidoServerClient.revokeDevice(trustedExternalUserId, deviceId);
    }
}
