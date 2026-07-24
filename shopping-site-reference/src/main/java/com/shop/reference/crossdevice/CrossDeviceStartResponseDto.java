package com.shop.reference.crossdevice;

/**
 * 購物網站前端拿到的「發起跨裝置 QR 登入」回應。
 *
 * <p>刻意不包含 {@code xdevId}——它是能力憑證（bearer capability），只透過 httpOnly
 * {@value CrossDeviceAuthenticationProxyController#POLL_COOKIE_NAME} cookie 傳遞給瀏覽器，
 * 不應該出現在前端 JS 讀得到的任何 JSON body 裡（見設計文件 6.4 節、
 * {@link CrossDevicePollSessionService} Javadoc）。
 */
public record CrossDeviceStartResponseDto(String qrUrl, String verificationCode, int expiresIn) {
}
