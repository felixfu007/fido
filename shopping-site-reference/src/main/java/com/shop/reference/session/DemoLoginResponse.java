package com.shop.reference.session;

/** {@code POST /shop/api/session/login-as} 與 {@code GET /shop/api/session/whoami} 共用的回應格式。 */
public record DemoLoginResponse(boolean loggedIn, String externalUserId, String message) {
}
