package com.shop.reference.authentication;

import com.shop.reference.authentication.jwt.FidoSessionJwtValidator;
import com.shop.reference.authentication.jwt.JwtValidationException;
import com.shop.reference.authentication.jwt.ValidatedFidoSession;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.AuthenticationOptionsRequest;
import com.shop.reference.fidoclient.dto.AuthenticationOptionsResponse;
import com.shop.reference.fidoclient.dto.AuthenticationResultRequest;
import com.shop.reference.fidoclient.dto.AuthenticationResultResponse;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 購物網站「用 FIDO 登入」流程代理，對應 docs/api-contract.md 第 3 節。
 *
 * <p>{@code /result} 是整個參考範例裡最重要的一段程式碼：登入成功與否，最終不是看
 * fido-server 回應 JSON 裡的 {@code verified} 欄位，而是看
 * {@link FidoSessionJwtValidator#validate(String)} 能否對 session JWT 驗簽成功
 * （理由見該類別 Javadoc）。只有通過 JWT 驗證，才會建立購物網站自己的 session。
 */
@RestController
@RequestMapping("/shop/api/fido/authentication")
public class AuthenticationProxyController {

    private final FidoServerClient fidoServerClient;
    private final FidoSessionJwtValidator jwtValidator;
    private final ShopSessionService shopSessionService;

    /**
     * 是否對 {@link ShopSessionService#COOKIE_NAME} cookie 設 {@code Secure} 屬性，與
     * {@link com.shop.reference.session.DemoLoginController#secureCookie} 同一個設定來源
     * （{@code shop.session.cookie.secure}，預設 {@code true}），見該欄位 Javadoc。
     */
    @Value("${shop.session.cookie.secure:true}")
    private boolean secureCookie;

    public AuthenticationProxyController(FidoServerClient fidoServerClient, FidoSessionJwtValidator jwtValidator,
                                          ShopSessionService shopSessionService) {
        this.fidoServerClient = fidoServerClient;
        this.jwtValidator = jwtValidator;
        this.shopSessionService = shopSessionService;
    }

    @PostMapping("/options")
    public AuthenticationOptionsResponse options(@RequestBody(required = false) AuthenticationOptionsRequest request) {
        AuthenticationOptionsRequest effective = request != null ? request : new AuthenticationOptionsRequest(null);
        return fidoServerClient.authenticationOptions(effective);
    }

    @PostMapping("/result")
    public ShopLoginResponse result(@Valid @RequestBody AuthenticationResultRequest request,
                                     HttpServletResponse httpResponse) {
        AuthenticationResultResponse fidoResponse = fidoServerClient.authenticationResult(request);

        if (fidoResponse.session() == null || fidoResponse.session().token() == null
                || fidoResponse.session().token().isBlank()) {
            // fido-server 依合約在 200 回應時一定會帶 session.token；如果沒有，代表上游行為
            // 與合約不符，購物網站不該猜測補救，直接視為失敗（見本次任務規格缺口回報）。
            throw new JwtValidationException("MISSING_SESSION_TOKEN",
                    "fido-server 回應 200 verified=" + fidoResponse.verified() + " 卻沒有帶 session.token，違反 api-contract.md 3.2 合約。");
        }

        // ---- 關鍵信任邊界：不相信 fidoResponse.verified()，只相信驗過簽章的 JWT ----
        ValidatedFidoSession validated = jwtValidator.validate(fidoResponse.session().token());

        // 額外的一致性檢查（defense-in-depth）：JWT 內的 sub 應該與 HTTP body 的 externalUserId
        // 一致；理論上兩者都來自同一次 fido-server 呼叫、不該不一致，但如果不一致，代表某處出了
        // 非預期狀況（例如中間有代理竄改了其中一個欄位），保守起見一律拒絕，不嘗試猜測誰對誰錯。
        if (!validated.externalUserId().equals(fidoResponse.externalUserId())) {
            throw new JwtValidationException("SUBJECT_MISMATCH",
                    "JWT sub=" + validated.externalUserId() + " 與回應 externalUserId=" + fidoResponse.externalUserId() + " 不一致。");
        }

        ShopSession shopSession = shopSessionService.createSession(
                validated.externalUserId(), validated.deviceId(), validated.credentialId());

        ResponseCookie cookie = ResponseCookie.from(ShopSessionService.COOKIE_NAME, shopSession.sessionId())
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofMinutes(30))
                // secure 值來自 shop.session.cookie.secure（預設 true，見 application.yml）。
                .secure(secureCookie)
                .sameSite("Lax")
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return new ShopLoginResponse(true, validated.externalUserId(), "FIDO 登入成功，已建立購物網站 session。");
    }
}
