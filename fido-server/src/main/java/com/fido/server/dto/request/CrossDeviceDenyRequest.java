package com.fido.server.dto.request;

/**
 * 對應 api-contract.md §3.4.E {@code POST .../sessions/{xdevId}/deny} request body
 * （手機 App 直連，xdevId capability）。
 *
 * <p>整個 body 選填（呼叫端可完全省略），{@code reason} 欄位本身也選填，列舉值
 * {@code USER_CANCELLED} / {@code NO_CREDENTIAL}。**僅供稽核分類，不影響狀態轉移結果或任何
 * 授權判斷**（無論哪個原因都轉 {@code DENIED}）——見
 * {@link com.fido.server.service.CrossDeviceLoginService} 對非列舉值的正規化處理
 * （不合法值一律正規化為 {@code UNSPECIFIED}，不因此拒絕請求）。
 */
public record CrossDeviceDenyRequest(String reason) {
}
