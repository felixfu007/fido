package com.fido.server.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * 【真實實作（部分）】WebAuthn attestationObject 頂層 CBOR 解碼（fmt / authData / attStmt）
 * 與 authenticatorData 結構解析。
 *
 * <p>本類別「有做」的事：把 base64url attestationObject 解成 fmt 字串、authData 原始
 * bytes、attStmt 原始 map，並呼叫 {@link AuthenticatorDataParser} 解出 rpIdHash / flags /
 * signCount / aaguid / credentialId / credentialPublicKey(COSE 原始 bytes)。
 *
 * <p>本類別「沒有做」的事（見對應介面卡 Javadoc）：
 * <ul>
 *   <li>attStmt 內簽章的密碼學驗證 — {@link AttestationStatementVerifier}</li>
 *   <li>Android Key Attestation X.509 憑證鏈驗證與 securityLevel 判讀 —
 *       {@link AndroidKeyAttestationChainValidator}</li>
 * </ul>
 */
@Component
public class AttestationObjectParser {

    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    @SuppressWarnings("unchecked")
    public ParsedAttestationObject parse(String attestationObjectBase64Url) {
        byte[] attestationObjectBytes;
        try {
            attestationObjectBytes = Base64.getUrlDecoder().decode(attestationObjectBase64Url);
        } catch (IllegalArgumentException e) {
            throw new WebAuthnValidationException("attestationObject 非合法 base64url");
        }

        Map<String, Object> top;
        try {
            top = cborMapper.readValue(attestationObjectBytes, Map.class);
        } catch (Exception e) {
            throw new WebAuthnValidationException("attestationObject 非合法 CBOR：" + e.getMessage());
        }

        String fmt = (String) top.get("fmt");
        Object authDataObj = top.get("authData");
        Object attStmtObj = top.get("attStmt");
        if (fmt == null || !(authDataObj instanceof byte[])) {
            throw new WebAuthnValidationException("attestationObject 缺少 fmt 或 authData");
        }
        byte[] authDataBytes = (byte[]) authDataObj;
        AuthenticatorData authData = AuthenticatorDataParser.parse(authDataBytes);
        Map<String, Object> attStmt = attStmtObj instanceof Map ? (Map<String, Object>) attStmtObj : Map.of();
        return new ParsedAttestationObject(fmt, authData, attStmt, authDataBytes);
    }
}
