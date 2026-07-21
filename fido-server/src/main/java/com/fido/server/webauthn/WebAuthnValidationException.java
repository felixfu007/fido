package com.fido.server.webauthn;

/**
 * WebAuthn 資料結構層級（clientDataJSON / attestationObject / authenticatorData）
 * 解析失敗的低階例外。由 service 層攔截並依情境轉譯為
 * {@code ATTESTATION_INVALID} 或 {@code ASSERTION_INVALID}（見 api-contract.md 1.4）。
 */
public class WebAuthnValidationException extends RuntimeException {

    public WebAuthnValidationException(String message) {
        super(message);
    }
}
