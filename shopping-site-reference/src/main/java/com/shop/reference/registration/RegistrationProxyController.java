package com.shop.reference.registration;

import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.RegistrationOptionsRequest;
import com.shop.reference.fidoclient.dto.RegistrationOptionsResponse;
import com.shop.reference.fidoclient.dto.RegistrationResultRequest;
import com.shop.reference.fidoclient.dto.RegistrationResultResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * <p><b>安全注意（本參考範例刻意簡化之處）</b>：docs/api-contract.md 2.1 明確要求
 * {@code externalUserId}「已用帳密登入」時才能發起註冊。真正的購物網站必須讓
 * {@code externalUserId} 來自後端自己已驗證的登入 session（例如
 * {@code SecurityContextHolder}），不可以信任前端請求 body 裡的值 —— 否則任何人都能對
 * 別人的帳號發起 FIDO 裝置註冊請求（即使最後 fido-server 那端的 ceremony 仍需要那個人的
 * 裝置配合完成攻擊者才能得逞，但這已經是一個不該存在的攻擊面）。本專案沒有實作真正的帳密
 * 登入系統（超出範圍），所以示範頁面讓使用者直接輸入 externalUserId 代表「目前已登入的
 * 購物網站帳號」；正式串接務必替換掉這一段。
 */
@RestController
@RequestMapping("/shop/api/fido/registration")
public class RegistrationProxyController {

    private final FidoServerClient fidoServerClient;

    public RegistrationProxyController(FidoServerClient fidoServerClient) {
        this.fidoServerClient = fidoServerClient;
    }

    @PostMapping("/options")
    public RegistrationOptionsResponse options(@Valid @RequestBody RegistrationOptionsRequest request) {
        // TODO（正式串接必做）：以下這行示範直接信任呼叫端傳入的 externalUserId；
        // 正式系統應改為 fidoServerClient.registrationOptions(new RegistrationOptionsRequest(
        //     currentLoggedInShopUser(), request.displayName(), request.deviceLabel()))
        return fidoServerClient.registrationOptions(request);
    }

    @PostMapping("/result")
    public ResponseEntity<RegistrationResultResponse> result(@Valid @RequestBody RegistrationResultRequest request) {
        RegistrationResultResponse response = fidoServerClient.registrationResult(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
