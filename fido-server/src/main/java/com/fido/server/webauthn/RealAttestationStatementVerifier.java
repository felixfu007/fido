package com.fido.server.webauthn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 【真實實作】attStmt（"android-key" 格式）密碼學簽章驗證，依 WebAuthn §8.4：
 *
 * <ol>
 *   <li>驗證 attStmt.sig 是否為 (authenticatorData || clientDataHash) 在 attStmt.x5c[0]
 *       （leaf 憑證）公鑰下、以 attStmt.alg 指定演算法產生的合法簽章</li>
 *   <li>驗證 attStmt.x5c[0] 的公鑰與 authenticatorData.attestedCredentialData 內的
 *       credentialPublicKey 相符（避免 attestation 憑證與實際註冊公鑰被調包）</li>
 * </ol>
 *
 * <p>Android Key Attestation 憑證鏈本身（是否鏈接到 Google root、securityLevel 判讀）由
 * {@link AndroidKeyAttestationChainValidator} 負責，不在此類別範圍內。
 */
@Component
@ConditionalOnProperty(prefix = "fido.attestation", name = "mode", havingValue = "real", matchIfMissing = true)
public class RealAttestationStatementVerifier implements AttestationStatementVerifier {

    private static final Logger log = LoggerFactory.getLogger(RealAttestationStatementVerifier.class);

    /** 本專案僅支援 Android Keystore 產生的 "android-key" attestation 格式（見 CLAUDE.md）。 */
    private static final String SUPPORTED_FORMAT = "android-key";

    private final CoseKeyParser coseKeyParser;
    private final CertificateFactory certificateFactory;

    public RealAttestationStatementVerifier(CoseKeyParser coseKeyParser) {
        this.coseKeyParser = coseKeyParser;
        try {
            this.certificateFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException("無法初始化 X.509 CertificateFactory", e);
        }
    }

    @Override
    public boolean verify(ParsedAttestationObject attestationObject, byte[] clientDataHash) {
        if (!SUPPORTED_FORMAT.equals(attestationObject.fmt())) {
            log.warn("不支援的 attestation 格式 fmt={}（本專案僅支援 {}）",
                    attestationObject.fmt(), SUPPORTED_FORMAT);
            return false;
        }

        Map<String, Object> attStmt = attestationObject.attStmt();
        Object algObj = attStmt.get("alg");
        Object sigObj = attStmt.get("sig");
        Object x5cObj = attStmt.get("x5c");
        if (!(algObj instanceof Number) || !(sigObj instanceof byte[] sig)
                || !(x5cObj instanceof List<?> x5cList) || x5cList.isEmpty()
                || !(x5cList.get(0) instanceof byte[] leafDer)) {
            log.warn("android-key attStmt 缺少或格式錯誤的 alg/sig/x5c 欄位");
            return false;
        }

        try {
            X509Certificate leaf = (X509Certificate) certificateFactory.generateCertificate(
                    new ByteArrayInputStream(leafDer));
            int coseAlg = ((Number) algObj).intValue();
            String jcaAlg = CoseAlgorithms.toJcaSignatureAlgorithm(coseAlg);

            Signature signature = Signature.getInstance(jcaAlg);
            signature.initVerify(leaf.getPublicKey());
            signature.update(attestationObject.rawAuthenticatorData());
            signature.update(clientDataHash);
            if (!signature.verify(sig)) {
                log.warn("android-key attStmt.sig 簽章驗證失敗");
                return false;
            }

            PublicKey attestedPublicKey = coseKeyParser.toPublicKey(
                    attestationObject.authenticatorData().credentialPublicKeyCose());
            if (!Arrays.equals(leaf.getPublicKey().getEncoded(), attestedPublicKey.getEncoded())) {
                log.warn("attStmt.x5c[0] 公鑰與 authenticatorData.credentialPublicKey 不一致");
                return false;
            }
            return true;
        } catch (WebAuthnValidationException e) {
            log.warn("attStmt 驗證失敗（結構錯誤）：{}", e.getMessage());
            return false;
        } catch (GeneralSecurityException e) {
            log.warn("attStmt 驗證失敗（密碼學錯誤）：{}", e.getMessage());
            return false;
        }
    }
}
