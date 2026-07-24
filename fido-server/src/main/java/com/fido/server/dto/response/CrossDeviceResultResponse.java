package com.fido.server.dto.response;

/**
 * 對應 api-contract.md 3.4.C response 200 body。proximity 不符**不阻擋**（S2 警示制），
 * 只在此欄位標記，登入仍照常轉為 {@code CONFIRMED}。
 */
public record CrossDeviceResultResponse(
        String status,
        ProximityInfo proximity
) {

    public record ProximityInfo(boolean checked, boolean mismatch) {
    }
}
