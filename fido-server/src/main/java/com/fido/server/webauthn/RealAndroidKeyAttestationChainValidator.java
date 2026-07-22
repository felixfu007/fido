package com.fido.server.webauthn;

import com.fido.server.domain.enums.SecurityLevel;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 【真實實作】Android Key Attestation X.509 憑證鏈驗證（對齊 CLAUDE.md「驗證 Android Key
 * Attestation 憑證鏈」與「強制 TEE/StrongBox，不通過則拒絕註冊」）。
 *
 * <p>驗證步驟：
 * <ol>
 *   <li>把 attStmt.x5c（leaf-first）逐一解成 {@link X509Certificate}</li>
 *   <li>逐張檢查效期（{@code checkValidity}），並驗證 chain[i] 是由 chain[i+1] 的私鑰簽發
 *       （{@code chain[i].verify(chain[i+1].getPublicKey())}）</li>
 *   <li>鏈尾（root）自我簽章完整性檢查，且其公鑰須等於 {@link TrustedRootCertificateStore}
 *       內建 Google 官方 root 之一的公鑰（比對公鑰而非整張憑證位元組，理由見
 *       {@code android-attestation-roots/SOURCE.txt}）</li>
 *   <li>解析 leaf 憑證的 Key Attestation extension（OID 1.3.6.1.4.1.11129.2.1.17），取出
 *       {@code attestationSecurityLevel} 與 {@code keymasterSecurityLevel}，取兩者較低者
 *       作為本次註冊實際獲得的硬體安全等級（保守判斷：任一欄位顯示較弱等級就不高估）</li>
 * </ol>
 *
 * <p>此外會比對 leaf 憑證 Key Attestation extension 內的 {@code attestationChallenge} 是否
 * 等於呼叫端傳入的 {@code expectedChallenge}（本次註冊 ceremony 的 challenge），防止攻擊者
 * 重放另一次 ceremony 取得的合法憑證鏈與簽章。比對採 {@link MessageDigest#isEqual} 而非
 * {@link Arrays#equals}，避免 timing attack。
 *
 * <p><b>已知範圍限制（尚未實作，見 dev-engineer 回報的開放問題）</b>：
 * <ul>
 *   <li>未做 Google 線上憑證撤銷清單查詢（該查詢需要對外連網呼叫 Google API，經與人類確認，
 *       第一版不做，維持現狀；見 {@code android-attestation-roots/SOURCE.txt}）。</li>
 *   <li>未解析 verifiedBootState / rootOfTrust 等 AuthorizationList 內其他欄位（超出本次
 *       任務要求範圍：僅需 securityLevel 判讀）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "fido.attestation", name = "mode", havingValue = "real", matchIfMissing = true)
public class RealAndroidKeyAttestationChainValidator implements AndroidKeyAttestationChainValidator {

    private static final Logger log = LoggerFactory.getLogger(RealAndroidKeyAttestationChainValidator.class);

    /** Android Key Attestation extension OID（KeyDescription ASN.1 結構）。 */
    private static final String KEY_ATTESTATION_EXTENSION_OID = "1.3.6.1.4.1.11129.2.1.17";

    private static final int KM_SECURITY_LEVEL_SOFTWARE = 0;
    private static final int KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1;
    private static final int KM_SECURITY_LEVEL_STRONG_BOX = 2;

    private final TrustedRootCertificateStore trustedRootCertificateStore;
    private final CertificateFactory certificateFactory;

    public RealAndroidKeyAttestationChainValidator(TrustedRootCertificateStore trustedRootCertificateStore) {
        this.trustedRootCertificateStore = trustedRootCertificateStore;
        try {
            this.certificateFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException("無法初始化 X.509 CertificateFactory", e);
        }
    }

    @Override
    public AttestationChainResult validate(ParsedAttestationObject attestationObject, byte[] expectedChallenge) {
        List<X509Certificate> chain;
        try {
            chain = extractCertChain(attestationObject);
        } catch (WebAuthnValidationException e) {
            log.warn("android-key attStmt.x5c 解析失敗：{}", e.getMessage());
            return new AttestationChainResult(false, null, "CHAIN_PARSE_ERROR");
        }
        if (chain.isEmpty()) {
            log.warn("android-key attStmt.x5c 缺失或為空");
            return new AttestationChainResult(false, null, "CHAIN_MISSING");
        }

        try {
            verifyChainSignaturesAndValidity(chain);
        } catch (GeneralSecurityException e) {
            log.warn("Android Key Attestation 憑證鏈簽章或效期驗證失敗：{}", e.getMessage());
            return new AttestationChainResult(false, null, "CHAIN_SIGNATURE_OR_VALIDITY_INVALID");
        }

        X509Certificate terminal = chain.get(chain.size() - 1);
        if (!isAnchoredToTrustedRoot(terminal)) {
            log.warn("Android Key Attestation 憑證鏈未連到受信任的 Google Hardware Attestation root");
            return new AttestationChainResult(false, null, "UNTRUSTED_ROOT");
        }

        KeyDescription keyDescription;
        try {
            keyDescription = parseKeyDescription(chain.get(0));
        } catch (WebAuthnValidationException e) {
            log.warn("Key Attestation extension 解析失敗：{}", e.getMessage());
            return new AttestationChainResult(false, null, "KEY_DESCRIPTION_PARSE_ERROR");
        }

        if (!MessageDigest.isEqual(keyDescription.attestationChallenge(), expectedChallenge)) {
            log.warn("Key Attestation extension attestationChallenge 與本次註冊 ceremony challenge 不符");
            return new AttestationChainResult(false, null, "CHALLENGE_MISMATCH");
        }

        int effectiveLevel = Math.min(keyDescription.attestationSecurityLevel(), keyDescription.keymasterSecurityLevel());
        SecurityLevel securityLevel = mapSecurityLevel(effectiveLevel);
        String rawLevel = describeLevels(keyDescription.attestationSecurityLevel(), keyDescription.keymasterSecurityLevel());
        return new AttestationChainResult(true, securityLevel, rawLevel);
    }

    private List<X509Certificate> extractCertChain(ParsedAttestationObject attestationObject) {
        Object x5cObj = attestationObject.attStmt().get("x5c");
        if (!(x5cObj instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<X509Certificate> certs = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof byte[] der)) {
                throw new WebAuthnValidationException("x5c 陣列元素非 bytes");
            }
            try {
                certs.add((X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(der)));
            } catch (CertificateException e) {
                throw new WebAuthnValidationException("x5c 憑證解析失敗：" + e.getMessage());
            }
        }
        return certs;
    }

    private void verifyChainSignaturesAndValidity(List<X509Certificate> chain) throws GeneralSecurityException {
        Date now = new Date();
        for (X509Certificate cert : chain) {
            cert.checkValidity(now);
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            chain.get(i).verify(chain.get(i + 1).getPublicKey());
        }
        // 鏈尾應為自我簽署的 root；驗證其自簽完整性（是否真的受信任見 isAnchoredToTrustedRoot）。
        X509Certificate terminal = chain.get(chain.size() - 1);
        terminal.verify(terminal.getPublicKey());
    }

    /**
     * 判斷鏈尾憑證是否可信：比對其公鑰（SubjectPublicKeyInfo）是否等於
     * {@link TrustedRootCertificateStore} 內任一受信任 root 的公鑰。採公鑰比對而非整張憑證
     * 比對的理由見 {@code android-attestation-roots/SOURCE.txt}。
     */
    private boolean isAnchoredToTrustedRoot(X509Certificate terminal) {
        byte[] terminalKeyBytes = terminal.getPublicKey().getEncoded();
        return trustedRootCertificateStore.getRoots().stream()
                .anyMatch(root -> Arrays.equals(root.getPublicKey().getEncoded(), terminalKeyBytes));
    }

    private KeyDescription parseKeyDescription(X509Certificate leaf) {
        byte[] extensionValue = leaf.getExtensionValue(KEY_ATTESTATION_EXTENSION_OID);
        if (extensionValue == null) {
            throw new WebAuthnValidationException("leaf 憑證缺少 Android Key Attestation extension");
        }
        try {
            // JDK getExtensionValue() 回傳的是「extnValue（OCTET STRING）」欄位本身的 DER
            // 編碼；須先解開這層 OCTET STRING，取得內含的 KeyDescription SEQUENCE DER bytes。
            ASN1OctetString outer = ASN1OctetString.getInstance(extensionValue);
            ASN1Sequence keyDescription = ASN1Sequence.getInstance(outer.getOctets());
            int attestationSecurityLevel = ASN1Enumerated.getInstance(keyDescription.getObjectAt(1))
                    .getValue().intValue();
            int keymasterSecurityLevel = ASN1Enumerated.getInstance(keyDescription.getObjectAt(3))
                    .getValue().intValue();
            byte[] attestationChallenge = ASN1OctetString.getInstance(keyDescription.getObjectAt(4)).getOctets();
            return new KeyDescription(attestationSecurityLevel, keymasterSecurityLevel, attestationChallenge);
        } catch (Exception e) {
            throw new WebAuthnValidationException("KeyDescription ASN.1 結構解析失敗：" + e.getMessage());
        }
    }

    private static SecurityLevel mapSecurityLevel(int level) {
        return switch (level) {
            case KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE;
            case KM_SECURITY_LEVEL_STRONG_BOX -> SecurityLevel.STRONG_BOX;
            default -> null; // Software(0) 或未知值：不視為達到硬體安全區
        };
    }

    private static String describeLevels(int attestationSecurityLevel, int keymasterSecurityLevel) {
        return "attestationSecurityLevel=" + levelName(attestationSecurityLevel)
                + ", keymasterSecurityLevel=" + levelName(keymasterSecurityLevel);
    }

    private static String levelName(int level) {
        return switch (level) {
            case KM_SECURITY_LEVEL_SOFTWARE -> "SOFTWARE";
            case KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT";
            case KM_SECURITY_LEVEL_STRONG_BOX -> "STRONG_BOX";
            default -> "UNKNOWN(" + level + ")";
        };
    }

    private record KeyDescription(int attestationSecurityLevel, int keymasterSecurityLevel,
                                   byte[] attestationChallenge) {
    }
}
