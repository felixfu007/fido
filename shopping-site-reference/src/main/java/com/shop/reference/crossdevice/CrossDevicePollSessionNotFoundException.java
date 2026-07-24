package com.shop.reference.crossdevice;

/**
 * poll 端點（{@code GET /shop/api/fido/authentication/cross-device/poll}）沒有收到有效的
 * {@value CrossDeviceAuthenticationProxyController#POLL_COOKIE_NAME} cookie，或 cookie 的值
 * 在伺服器端對映（{@link CrossDevicePollSessionService}）裡查無對應的 {@code xdevId} 時拋出。
 *
 * <p><b>安全用途</b>：對齊設計文件 6.4 節「poll 端點只認 cookie，不接受用查詢參數帶
 * {@code xdevId}」——本 controller 的 poll 方法本來就不接受任何 {@code xdevId} 請求參數，
 * 即使呼叫端在 query string 夾帶一個看似有效的 {@code xdevId}，也完全不會被讀取；唯一的輸入
 * 只有 {@code XDEV_POLL} cookie。找不到對映時一律拋這個例外、回一致的 400 錯誤，不區分
 * 「cookie 缺失」與「cookie 值不對映任何 session」两種情況，避免對第三方洩漏任何可用於猜測
 * 有效 poll secret 的資訊。
 */
public class CrossDevicePollSessionNotFoundException extends RuntimeException {

    public CrossDevicePollSessionNotFoundException(String message) {
        super(message);
    }
}
