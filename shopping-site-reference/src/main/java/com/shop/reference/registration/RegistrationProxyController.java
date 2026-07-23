package com.shop.reference.registration;

import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.RegistrationOptionsRequest;
import com.shop.reference.fidoclient.dto.RegistrationOptionsResponse;
import com.shop.reference.fidoclient.dto.RegistrationResultRequest;
import com.shop.reference.fidoclient.dto.RegistrationResultResponse;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 購物網站「新增 FIDO 裝置」流程代理，對應 docs/api-contract.md 第 2 節。
 *
 * <p>流程：購物網站前端呼叫這裡的 {@code /options} 取得 {@code publicKeyCredentialCreationOptions}，
 * 交給瀏覽器 {@code navigator.credentials.create()}；完成後把結果送回這裡的 {@code /result}，
 * 由本後端原封轉呼叫 fido-server 驗證並回傳結果。
 *
 * <p><b>安全模式（已示範正確做法）</b>：docs/api-contract.md 2.1 明確要求
 * {@code externalUserId}「已用帳密登入」時才能發起註冊。這兩個端點都不信任呼叫端請求裡的
 * {@code externalUserId}，一律透過 {@link ShopSessionService#requireSession(String)} 從購物網站
 * 自己的登入 session（{@link ShopSessionService#COOKIE_NAME} cookie）取得 —— 沒有有效 session
 * 直接以 401 拒絕；如果請求裡另外夾帶了與 session 不同的 externalUserId，也會被
 * {@link ShopSessionService#resolveExternalUserId(ShopSession, String)} 明確拒絕（403），
 * 而不是沉默忽略。真正呼叫 fido-server 時使用的 {@code externalUserId} 一律是從 session
 * 解析出來、可信任的值。
 *
 * <p>本範例的登入 session 由示範用假登入端點
 * {@link com.shop.reference.session.DemoLoginController#loginAs} 建立（正式串接請換成
 * 真正的帳密系統）；也可以由 {@link com.shop.reference.authentication.AuthenticationProxyController}
 * 的 FIDO 登入流程建立（例如已用某台裝置 FIDO 登入後，要再註冊一台新裝置）—— 兩者都是
 * 「已驗證的購物網站 session」，本 controller 不在乎 session 是怎麼建立的。
 */
@RestController
@RequestMapping("/shop/api/fido/registration")
public class RegistrationProxyController {

    private final FidoServerClient fidoServerClient;
    private final ShopSessionService shopSessionService;

    public RegistrationProxyController(FidoServerClient fidoServerClient, ShopSessionService shopSessionService) {
        this.fidoServerClient = fidoServerClient;
        this.shopSessionService = shopSessionService;
    }

    @PostMapping("/options")
    public RegistrationOptionsResponse options(
            @Valid @RequestBody RegistrationOptionsRequest request,
            @CookieValue(name = ShopSessionService.COOKIE_NAME, required = false) String sessionId) {
        ShopSession session = shopSessionService.requireSession(sessionId);
        String trustedExternalUserId = shopSessionService.resolveExternalUserId(session, request.externalUserId());

        RegistrationOptionsRequest trustedRequest =
                new RegistrationOptionsRequest(trustedExternalUserId, request.displayName(), request.deviceLabel());
        return fidoServerClient.registrationOptions(trustedRequest);
    }

    @PostMapping("/result")
    public ResponseEntity<RegistrationResultResponse> result(
            @Valid @RequestBody RegistrationResultRequest request,
            @CookieValue(name = ShopSessionService.COOKIE_NAME, required = false) String sessionId) {
        ShopSession session = shopSessionService.requireSession(sessionId);
        String trustedExternalUserId = shopSessionService.resolveExternalUserId(session, request.externalUserId());

        RegistrationResultRequest trustedRequest = new RegistrationResultRequest(
                request.ceremonyId(), trustedExternalUserId, request.credential(), request.deviceLabel());
        RegistrationResultResponse response = fidoServerClient.registrationResult(trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
