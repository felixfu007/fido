package com.fido.server.webauthn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【介面卡 — 骨架 stub，非真實密碼學驗證，正式上線前必須替換】
 *
 * <p>不還原 COSE 公鑰、不做任何 ECDSA/RSA 驗簽運算；僅檢查 signature 非空作為
 * 最基本的結構檢查，讓上層流程（sign counter 檢查、session JWT 簽發等）可被端對端測試。
 */
@Component
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
