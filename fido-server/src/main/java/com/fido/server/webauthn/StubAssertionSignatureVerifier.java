package com.fido.server.webauthn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 【介面卡 — 骨架 stub，非真實密碼學驗證】
 *
 * <p>不還原 COSE 公鑰、不做任何 ECDSA/RSA 驗簽運算；僅檢查 signature 非空作為
 * 最基本的結構檢查。
 *
 * <p>僅在明確設定 {@code fido.attestation.mode=stub} 時才會啟用；預設一律使用
 * {@link RealAssertionSignatureVerifier}，正式路徑（含登入）不會走到本類別。
 */
@Component
@ConditionalOnProperty(prefix = "fido.attestation", name = "mode", havingValue = "stub")
public class StubAssertionSignatureVerifier implements AssertionSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(StubAssertionSignatureVerifier.class);

    @Override
    public boolean verify(byte[] coseCredentialPublicKey, byte[] authenticatorDataRaw, byte[] clientDataJsonBytes,
                           byte[] signature, int coseAlg) {
        log.warn("[STUB-NOT-REAL-CRYPTO] AssertionSignatureVerifier 未實作真實簽章驗證，"
                + "僅檢查 signature 非空。coseAlg={}", coseAlg);
        return signature != null && signature.length > 0;
    }
}
