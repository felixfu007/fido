package com.fido.server.webauthn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;

/**
 * 【真實實作】WebAuthn assertion（登入）簽章驗證：以註冊時存下的 COSE_Key 公鑰，驗證
 * signature 是否為 {@code authenticatorData || SHA-256(clientDataJSON)} 的合法簽章。
 *
 * <p>這是登入流程「使用者真的持有已註冊裝置私鑰」的唯一密碼學保證來源；驗證失敗必須
 * 回傳 false（絕不可因例外而預設通過）。
 */
@Component
@ConditionalOnProperty(prefix = "fido.attestation", name = "mode", havingValue = "real", matchIfMissing = true)
public class RealAssertionSignatureVerifier implements AssertionSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(RealAssertionSignatureVerifier.class);

    private final CoseKeyParser coseKeyParser;

    public RealAssertionSignatureVerifier(CoseKeyParser coseKeyParser) {
        this.coseKeyParser = coseKeyParser;
    }

    @Override
    public boolean verify(byte[] coseCredentialPublicKey, byte[] authenticatorDataRaw, byte[] clientDataJsonBytes,
                           byte[] signature, int coseAlg) {
        if (signature == null || signature.length == 0 || coseCredentialPublicKey == null
                || authenticatorDataRaw == null || clientDataJsonBytes == null) {
            log.warn("assertion 簽章驗證輸入不完整");
            return false;
        }
        try {
            PublicKey publicKey = coseKeyParser.toPublicKey(coseCredentialPublicKey);
            String jcaAlg = CoseAlgorithms.toJcaSignatureAlgorithm(coseAlg);

            Signature sig = Signature.getInstance(jcaAlg);
            sig.initVerify(publicKey);
            sig.update(authenticatorDataRaw);
            sig.update(sha256(clientDataJsonBytes));
            return sig.verify(signature);
        } catch (WebAuthnValidationException e) {
            log.warn("assertion 簽章驗證失敗（結構錯誤）：{}", e.getMessage());
            return false;
        } catch (GeneralSecurityException e) {
            log.warn("assertion 簽章驗證失敗（密碼學錯誤）：{}", e.getMessage());
            return false;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
