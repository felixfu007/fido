package com.shop.reference.session;

import jakarta.validation.constraints.NotBlank;

/**
 * {@link DemoLoginController#loginAs(DemoLoginRequest, jakarta.servlet.http.HttpServletResponse)}
 * 的 request body —— 見該 controller Javadoc：這是示範用假登入，{@code externalUserId}
 * 在這裡直接由呼叫端宣稱「就是我」且不做任何帳密驗證，只有這一個端點可以這樣做。
 */
public record DemoLoginRequest(@NotBlank String externalUserId) {
}
