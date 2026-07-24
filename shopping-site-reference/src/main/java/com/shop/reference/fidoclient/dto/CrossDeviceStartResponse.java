package com.shop.reference.fidoclient.dto;

/**
 * 對應 docs/api-contract.md §3.4.A response 200 body。
 *
 * <p>{@code xdevId} 是能力憑證（bearer capability），購物網站後端**不得**把它原封轉發給前端
 * JS——必須用 httpOnly cookie 或伺服器端對映把它綁定到發起請求的桌機瀏覽器（見設計文件 6.4 節
 * 與 {@link com.shop.reference.crossdevice.CrossDevicePollSessionService}），本範例採用後者。
 */
public record CrossDeviceStartResponse(String xdevId, String qrUrl, String verificationCode, int expiresIn) {
}
