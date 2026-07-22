package com.fido.server.webauthn;

/**
 * attStmt（attestation statement）密碼學簽章驗證介面。
 *
 * <p>正式路徑（{@code fido.attestation.mode=real}，預設）實作為
 * {@link RealAttestationStatementVerifier}：依 attStmt.fmt（本專案對齊 "android-key"）
 * 驗證 attStmt.sig 是否為 x5c[0] 憑證私鑰對
 * {@code (authenticatorData || SHA-256(clientDataJSON))} 的合法簽章，並確認 x5c[0]
 * 公鑰與 credentialPublicKey 一致。測試環境可設 {@code fido.attestation.mode=stub}
 * 切回 {@link StubAttestationStatementVerifier}（固定回傳「通過」，不做任何簽章驗算）。
 */
public interface AttestationStatementVerifier {

    /**
     * @param attestationObject 已解析的 attestationObject
     * @param clientDataHash    SHA-256(clientDataJSON 原始 bytes)
     * @return true 表示簽章驗證通過（stub 版本恆為 true）
     */
    boolean verify(ParsedAttestationObject attestationObject, byte[] clientDataHash);
}
