package com.shop.reference.authentication.jwt;

/**
 * fido-server 簽發的 session JWT 未通過購物網站端獨立驗證時拋出。{@code reasonCode}
 * 供 log / 錯誤回應分流（不是 fido-server 的錯誤碼，這是「購物網站自己驗 JWT」這一關
 * 產生的錯誤，與 docs/api-contract.md 1.4 的 fido-server 錯誤碼表是兩回事）。
 */
public class JwtValidationException extends RuntimeException {

    private final String reasonCode;

    public JwtValidationException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public JwtValidationException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
