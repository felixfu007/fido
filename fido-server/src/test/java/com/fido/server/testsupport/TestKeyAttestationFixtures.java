package com.fido.server.testsupport;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 【測試專用 fixture】組出「密碼學上合法」的 Android Key Attestation 憑證鏈，取代舊 stub
 * 年代測試仰賴的假資料（隨機 bytes / 空 attStmt）。
 *
 * <p>使用一組僅存在於單次 JVM 執行期間、隨機產生的自簽測試 root（<b>不是</b> Google 私鑰，
 * 也不可能是——Google 的 attestation root 私鑰不對外流通）。這組測試 root 只在測試 context
 * 生效：{@code RegistrationAndAuthenticationFlowTest} 透過 {@code @TestConfiguration} 把
 * {@link com.fido.server.webauthn.TrustedRootCertificateStore} 換成內含本測試 root 的版本，
 * 正式部署仍使用內建的 Google 官方 root 集合（見
 * {@code src/main/resources/android-attestation-roots/}），兩者互不影響。
 */
public final class TestKeyAttestationFixtures {

    /** Android Key Attestation extension OID，須與 {@code RealAndroidKeyAttestationChainValidator} 一致。 */
    private static final String KEY_ATTESTATION_EXTENSION_OID = "1.3.6.1.4.1.11129.2.1.17";

    public static final int SECURITY_LEVEL_SOFTWARE = 0;
    public static final int SECURITY_LEVEL_TEE = 1;
    public static final int SECURITY_LEVEL_STRONG_BOX = 2;

    private static final X500Name ROOT_NAME = new X500Name("CN=Test Android Keystore Attestation Root,O=FIDO Test Fixtures");

    /** 本次 JVM 執行期間固定、僅供測試使用的 root 金鑰對與自簽憑證。 */
    public static final KeyPair ROOT_KEY_PAIR = generateEcKeyPair();
    public static final X509Certificate ROOT_CERTIFICATE = selfSignRoot(ROOT_KEY_PAIR);

    private TestKeyAttestationFixtures() {
    }

    public static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
            keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
            return keyPairGenerator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("測試 EC 金鑰對產生失敗", e);
        }
    }

    /**
     * 產生一張以 {@link #ROOT_KEY_PAIR} 簽發、內含 Android Key Attestation extension 的 leaf
     * 憑證。{@code attestationSecurityLevel} 與 {@code keymasterSecurityLevel} 使用相同值。
     *
     * @param leafPublicKey         這張 leaf 憑證所擔保的公鑰；須與 WebAuthn
     *                              attestationObject.authData.credentialPublicKey 對應同一把金鑰，
     *                              否則 {@code RealAttestationStatementVerifier} 的公鑰一致性檢查會失敗
     * @param securityLevel         寫入 extension 的 security level（0=Software/1=TEE/2=StrongBox）
     * @param attestationChallenge  寫入 extension 的 attestationChallenge 欄位；
     *                              {@code RealAndroidKeyAttestationChainValidator} 會比對此欄位是否
     *                              等於呼叫端傳入的本次 ceremony challenge，須傳入真實 challenge
     *                              bytes 才能通過驗證（除非該測試案例的目的正是測試不符情境）
     */
    public static X509Certificate buildLeafCertificate(PublicKey leafPublicKey, int securityLevel,
                                                         byte[] attestationChallenge) {
        return buildLeafCertificate(leafPublicKey, securityLevel, securityLevel, attestationChallenge);
    }

    public static X509Certificate buildLeafCertificate(PublicKey leafPublicKey, int attestationSecurityLevel,
                                                         int keymasterSecurityLevel, byte[] attestationChallenge) {
        try {
            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=Test Android Keystore Key,O=FIDO Test Fixtures");
            BigInteger serial = BigInteger.valueOf(now.toEpochMilli());

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    ROOT_NAME, serial,
                    Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(1))),
                    subject, leafPublicKey);

            ASN1EncodableVector keyDescription = new ASN1EncodableVector();
            keyDescription.add(new ASN1Integer(4)); // attestationVersion（本專案驗證邏輯不檢查此值）
            keyDescription.add(new ASN1Enumerated(attestationSecurityLevel));
            keyDescription.add(new ASN1Integer(4)); // keymasterVersion（同上）
            keyDescription.add(new ASN1Enumerated(keymasterSecurityLevel));
            keyDescription.add(new DEROctetString(attestationChallenge != null ? attestationChallenge : new byte[0]));
            keyDescription.add(new DEROctetString(new byte[0])); // uniqueId（本專案不使用）
            keyDescription.add(new DERSequence()); // softwareEnforced（本專案不解析內容）
            keyDescription.add(new DERSequence()); // teeEnforced / hardwareEnforced（本專案不解析內容）
            builder.addExtension(new ASN1ObjectIdentifier(KEY_ATTESTATION_EXTENSION_OID), false,
                    new DERSequence(keyDescription));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(ROOT_KEY_PAIR.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("測試 leaf 憑證產生失敗", e);
        }
    }

    private static X509Certificate selfSignRoot(KeyPair rootKeyPair) {
        try {
            Instant now = Instant.now();
            BigInteger serial = BigInteger.valueOf(now.toEpochMilli());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    ROOT_NAME, serial,
                    Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(3650))),
                    ROOT_NAME, rootKeyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(rootKeyPair.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("測試 root 憑證產生失敗", e);
        }
    }
}
