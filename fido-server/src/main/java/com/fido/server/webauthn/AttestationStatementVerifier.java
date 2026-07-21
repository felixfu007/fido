package com.fido.server.webauthn;

/**
 * attStmt（attestation statement）密碼學簽章驗證介面。
 *
 * <p><b>【介面卡 — 尚未實作真實密碼學，僅骨架】</b>唯一實作為
 * {@link StubAttestationStatementVerifier}，固定回傳「通過」，不做任何簽章驗算。
 * 正式版需依 attStmt.fmt（本專案對齊 "android-key"）驗證 attStmt.sig 是否為
 * x5c[0] 憑證私鑰對 (authenticatorData || SHA-256(clientDataJSON)) 的合法簽章。
 * 建議導入 WebAuthn4J 取代本手刻介面。
 */
public interface AttestationStatementVerifier {

    /**
     * @param attestationObject 已解析的 attestationObject
     * @param clientDataHash    SHA-256(clientDataJSON 原始 bytes)
     * @return true 表示簽章驗證通過（stub 版本恆為 true）
     */
    boolean verify(ParsedAttestationObject attestationObject, byte[] clientDataHash);
}
