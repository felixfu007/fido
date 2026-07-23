package com.shop.reference;

import com.shop.reference.config.FidoClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 「採用 FIDO 登入的購物網站」參考串接範例。
 *
 * <p>這不是一個真正的電商系統（沒有商品/購物車/金流），純粹示範購物網站後端要如何呼叫
 * fido-server 的 REST API（{@code docs/api-contract.md}）完成：
 * <ol>
 *   <li>註冊流程代理（{@link com.shop.reference.registration.RegistrationProxyController}）</li>
 *   <li>登入流程代理 + session JWT 驗證（{@link com.shop.reference.authentication.AuthenticationProxyController}）</li>
 *   <li>裝置管理代理（{@link com.shop.reference.device.DeviceProxyController}）</li>
 * </ol>
 *
 * <p>對齊 CLAUDE.md「身分權威來源：購物網站既有帳密系統」：本專案刻意不實作真正的帳密登入
 * 系統（那是購物網站既有系統的職責，不在本專案範圍），僅用一個最小的示範 session
 * （{@link com.shop.reference.session.ShopSessionService}）模擬「FIDO 驗證通過後，購物網站
 * 建立自己的登入 session」這一步。
 */
@SpringBootApplication
@EnableConfigurationProperties(FidoClientProperties.class)
public class ShoppingSiteReferenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShoppingSiteReferenceApplication.class, args);
    }
}
