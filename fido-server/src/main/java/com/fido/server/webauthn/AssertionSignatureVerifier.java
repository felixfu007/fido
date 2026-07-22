package com.fido.server.webauthn;

/**
 * WebAuthn assertion（登入）簽章驗證介面：以已儲存的 COSE_Key 公鑰驗證
 * signature 是否為 (authenticatorData || SHA-256(clientDataJSON)) 的合法簽章。
 *
 * <p>正式路徑（{@code fido.attestation.mode=real}，預設）實作為
 * {@link RealAssertionSignatureVerifier}：先由 COSE_Key bytes 還原
 * {@code java.security.PublicKey}（EC2/P-256、P-384、P-521 對應 ES256/ES384/ES512；
 * RSA 對應 RS256/RS384/RS512），再以對應演算法驗證簽章。測試環境可設
 * {@code fido.attestation.mode=stub} 切回 {@link StubAssertionSignatureVerifier}
 * （僅檢查 signature 非空，非真實驗簽）。
 */
public interface AssertionSignatureVerifier {

    /**
     * @param coseCredentialPublicKey 註冊時存下的 COSE_Key 公鑰原始 bytes
     * @param authenticatorDataRaw    本次 assertion 的 authenticatorData 原始 bytes
     * @param clientDataJsonBytes     本次 assertion 的 clientDataJSON 原始 bytes（用於算 SHA-256）
     * @param signature               本次 assertion 的簽章原始 bytes
     * @param coseAlg                 註冊時記錄的 COSE 演算法 ID
     * @return true 表示簽章驗證通過（stub 版本僅做「非空」結構檢查，非真實驗簽）
     */
    boolean verify(byte[] coseCredentialPublicKey, byte[] authenticatorDataRaw, byte[] clientDataJsonBytes,
                    byte[] signature, int coseAlg);
}
