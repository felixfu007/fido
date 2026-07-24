package com.shop.reference.crossdevice;

/**
 * 購物網站前端輪詢跨裝置 QR 登入狀態的回應。
 *
 * <p>{@code status} 為 {@code PENDING}/{@code SCANNED} 時前端應繼續輪詢；{@code CONFIRMED}
 * 時登入已成功、{@code SHOP_SESSION} cookie 已於同一次回應設好（見
 * {@link CrossDeviceAuthenticationProxyController#poll}）；{@code DENIED}/{@code EXPIRED}
 * 時前端應停止輪詢並顯示 {@code message}。
 */
public record CrossDevicePollResponseDto(String status, String message, String externalUserId) {

    public static CrossDevicePollResponseDto waiting(String status) {
        return new CrossDevicePollResponseDto(status, null, null);
    }

    public static CrossDevicePollResponseDto confirmed(String externalUserId) {
        return new CrossDevicePollResponseDto("CONFIRMED", "FIDO 跨裝置登入成功，已建立購物網站 session。", externalUserId);
    }

    public static CrossDevicePollResponseDto denied() {
        return new CrossDevicePollResponseDto("DENIED", "使用者已在手機端取消或拒絕，請重新產生 QR code。", null);
    }

    public static CrossDevicePollResponseDto expired() {
        return new CrossDevicePollResponseDto("EXPIRED", "QR code 已逾時，請重新產生。", null);
    }
}
