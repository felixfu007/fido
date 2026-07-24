package com.shop.reference.authentication;

import com.shop.reference.authentication.jwt.FidoSessionJwtValidator;
import com.shop.reference.authentication.jwt.JwtValidationException;
import com.shop.reference.authentication.jwt.ValidatedFidoSession;
import com.shop.reference.session.ShopSession;
import com.shop.reference.session.ShopSessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 「拿到 fido-server 簽發的 session JWT 之後，收尾建立購物網站自己 session」這段邏輯的
 * 唯一實作，供 {@link AuthenticationProxyController#result} （同裝置，§3.2）與
 * {@code CrossDeviceAuthenticationProxyController#poll}（跨裝置 QR，情境三，§3.4）兩處共用
 * ——避免同一段「只信驗過簽的 JWT、不信任何布林欄位」的信任邊界邏輯，在兩個 controller 裡
 * 各自維護一份、之後改一邊忘了改另一邊（見 CLAUDE.md「桌機 QR 掃碼跨裝置登入」任務要求）。
 *
 * <p><b>信任邊界（與 {@link FidoSessionJwtValidator} 完全一致，不重新發明）</b>：不相信
 * fido-server 回應裡任何 {@code verified}/{@code confirmed}/{@code status} 這類布林或字串
 * 欄位，只相信 {@link FidoSessionJwtValidator#validate(String)} 對 session JWT 驗簽成功的
 * 結果。只有通過 JWT 驗證，才會呼叫 {@link ShopSessionService#createSession} 建立購物網站自己
 * 的 session、設下 {@link ShopSessionService#COOKIE_NAME} cookie。
 *
 * <p>驗證通過後，依 {@link ValidatedFidoSession#isCrossDeviceLogin()}（JWT {@code amr} 含
 * {@code "xdev"}）把「這次登入是否經跨裝置 QR 較弱路徑取得」一併寫進新建立的
 * {@link ShopSession}，供 {@link com.shop.reference.device.DeviceProxyController#revoke}
 * 示範的 step-up 檢查使用（docs/api-contract.md §1.3 / D17）。
 */
@Component
public class ShopLoginFinalizer {

    private final FidoSessionJwtValidator jwtValidator;
    private final ShopSessionService shopSessionService;

    /**
     * 是否對 {@link ShopSessionService#COOKIE_NAME} cookie 設 {@code Secure} 屬性，與
     * {@link com.shop.reference.session.DemoLoginController#secureCookie} 同一個設定來源
     * （{@code shop.session.cookie.secure}，預設 {@code true}）。
     */
    @Value("${shop.session.cookie.secure:true}")
    private boolean secureCookie;

    public ShopLoginFinalizer(FidoSessionJwtValidator jwtValidator, ShopSessionService shopSessionService) {
        this.jwtValidator = jwtValidator;
        this.shopSessionService = shopSessionService;
    }

    /**
     * @param sessionJwtToken             fido-server 回應內的 session JWT（{@code null}/空白
     *                                     代表上游違反合約沒有帶 token，呼叫端應在呼叫本方法前
     *                                     自行判斷是否要用更具體的診斷訊息拋出，本方法在這種
     *                                     情況下仍會拋出一個通用版本的 {@code MISSING_SESSION_TOKEN}）
     * @param expectedExternalUserIdOrNull 若呼叫端手上還有另一個「fido-server 回應本身宣稱」的
     *                                     externalUserId 可以拿來做一致性檢查就傳入（例如 §3.2
     *                                     /result 回應內的 externalUserId 欄位），沒有就傳
     *                                     {@code null} 跳過此檢查——cross-device §3.4 的
     *                                     status 回應本來就不帶這個欄位，無從比對。
     * @param httpResponse                 用來附加 {@code Set-Cookie} header 的回應物件
     */
    public FinalizedLogin finalize(String sessionJwtToken, String expectedExternalUserIdOrNull,
                                    HttpServletResponse httpResponse) {
        if (sessionJwtToken == null || sessionJwtToken.isBlank()) {
            throw new JwtValidationException("MISSING_SESSION_TOKEN",
                    "fido-server 回應中沒有帶 session token，違反 docs/api-contract.md 合約。");
        }

        // ---- 關鍵信任邊界：不相信任何布林/字串狀態欄位，只相信驗過簽章的 JWT ----
        ValidatedFidoSession validated = jwtValidator.validate(sessionJwtToken);

        if (expectedExternalUserIdOrNull != null && !expectedExternalUserIdOrNull.isBlank()
                && !validated.externalUserId().equals(expectedExternalUserIdOrNull)) {
            throw new JwtValidationException("SUBJECT_MISMATCH",
                    "JWT sub=" + validated.externalUserId() + " 與回應 externalUserId="
                            + expectedExternalUserIdOrNull + " 不一致。");
        }

        ShopSession shopSession = shopSessionService.createSession(
                validated.externalUserId(), validated.deviceId(), validated.credentialId(),
                validated.isCrossDeviceLogin());

        ResponseCookie cookie = ResponseCookie.from(ShopSessionService.COOKIE_NAME, shopSession.sessionId())
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofMinutes(30))
                .secure(secureCookie)
                .sameSite("Lax")
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return new FinalizedLogin(validated, shopSession);
    }

    /** {@link #finalize} 的結果：驗證後的 JWT 內容與（已建立、已設好 cookie 的）購物網站 session。 */
    public record FinalizedLogin(ValidatedFidoSession validated, ShopSession shopSession) {
    }
}
