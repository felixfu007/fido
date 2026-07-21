package com.fido.server.webauthn;

import java.util.Map;

/**
 * 解析後的 attestationObject（CBOR 頂層解碼結果）。
 *
 * @param fmt                  attestation statement 格式（本專案對齊 "android-key"）
 * @param authenticatorData    已解析的 authenticatorData 結構
 * @param attStmt              attestation statement 原始 CBOR map（sig / x5c 等，未做密碼學驗證）
 * @param rawAuthenticatorData authenticatorData 原始 bytes（供簽章驗證使用）
 */
public record ParsedAttestationObject(
        String fmt,
        AuthenticatorData authenticatorData,
        Map<String, Object> attStmt,
        byte[] rawAuthenticatorData
) {
}
