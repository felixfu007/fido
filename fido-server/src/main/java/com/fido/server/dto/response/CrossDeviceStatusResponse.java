package com.fido.server.dto.response;

/**
 * 對應 api-contract.md 3.4.D response 200 body。{@code session} 只在 {@code status="CONFIRMED"}
 * 時非 null（{@code spring.jackson.default-property-inclusion=non_null} 會在其餘狀態自動省略
 * 該欄位）；JWT 只回一次，回後 session 立即轉 {@code CONSUMED}。
 */
public record CrossDeviceStatusResponse(
        String status,
        SessionInfo session,
        Warnings warnings
) {

    public record SessionInfo(String token, String tokenType, int expiresIn) {
    }

    public record Warnings(Boolean proximityMismatch) {
    }
}
