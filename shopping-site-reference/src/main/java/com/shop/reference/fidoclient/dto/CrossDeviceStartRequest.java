package com.shop.reference.fidoclient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 對應 docs/api-contract.md §3.4.A {@code POST /api/v1/authentication/cross-device/sessions}
 * request body。
 *
 * <p>{@code desktopClientIp} 是桌機瀏覽器的真實 client IP，由購物網站後端從自己收到的請求
 * （{@link com.shop.reference.util.ClientIpResolver}）取得後轉發——fido-server 端看到的直接
 * 來源是購物網站後端的 IP，不是桌機，故必須由後端誠實轉發（見設計文件 5.2.4 附註）。
 */
public record CrossDeviceStartRequest(@NotBlank String desktopClientIp) {
}
