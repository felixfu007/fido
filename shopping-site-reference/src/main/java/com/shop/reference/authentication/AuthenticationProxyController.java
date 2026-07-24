package com.shop.reference.authentication;

import com.shop.reference.authentication.jwt.FidoSessionJwtValidator;
import com.shop.reference.authentication.jwt.JwtValidationException;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.AuthenticationOptionsRequest;
import com.shop.reference.fidoclient.dto.AuthenticationOptionsResponse;
import com.shop.reference.fidoclient.dto.AuthenticationResultRequest;
import com.shop.reference.fidoclient.dto.AuthenticationResultResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 購物網站「用 FIDO 登入」流程代理，對應 docs/api-contract.md 第 3 節。
 *
 * <p>{@code /result} 是整個參考範例裡最重要的一段程式碼：登入成功與否，最終不是看
 * fido-server 回應 JSON 裡的 {@code verified} 欄位，而是看
 * {@link FidoSessionJwtValidator#validate(String)} 能否對 session JWT 驗簽成功
 * （理由見該類別 Javadoc）。只有通過 JWT 驗證，才會建立購物網站自己的 session。實際的
 * 「驗 JWT → 建 session → 設 cookie」收尾邏輯已抽到 {@link ShopLoginFinalizer}，與跨裝置 QR
 * 登入（{@code CrossDeviceAuthenticationProxyController#poll}）共用同一份實作。
 */
@RestController
@RequestMapping("/shop/api/fido/authentication")
public class AuthenticationProxyController {

    private final FidoServerClient fidoServerClient;
    private final ShopLoginFinalizer shopLoginFinalizer;

    public AuthenticationProxyController(FidoServerClient fidoServerClient, ShopLoginFinalizer shopLoginFinalizer) {
        this.fidoServerClient = fidoServerClient;
        this.shopLoginFinalizer = shopLoginFinalizer;
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

        ShopLoginFinalizer.FinalizedLogin finalized = shopLoginFinalizer.finalize(
                fidoResponse.session().token(), fidoResponse.externalUserId(), httpResponse);

        return new ShopLoginResponse(true, finalized.validated().externalUserId(), "FIDO 登入成功，已建立購物網站 session。");
    }
}
