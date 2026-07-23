package com.shop.reference.authentication;

/**
 * 購物網站前端拿到的登入結果（不是 fido-server 的回應，是本站包裝過的結果）。
 * 前端只需要知道「有沒有登入成功」，不需要也不應該看到 fido-server 的 session JWT 本身
 * ——JWT 已經在後端被消費並換成購物網站自己的 session cookie（見
 * {@link AuthenticationProxyController}）。
 */
public record ShopLoginResponse(boolean loggedIn, String externalUserId, String message) {
}
