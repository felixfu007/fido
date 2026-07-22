package com.fido.server.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【測試專用 fixture】組出「密碼學上合法」的 WebAuthn ceremony 資料（clientDataJSON /
 * authenticatorData / attestationObject / assertion signature），供端對端測試組出真實請求
 * body，走 {@code fido.attestation.mode=real} 的真實密碼學驗證路徑（而非 stub）。
 *
 * <p>原本是 {@code RegistrationAndAuthenticationFlowTest} 的私有方法，抽出成獨立
 * public 類別，讓其他端對端測試（例如切換 persistence 層為 JPA + H2 的測試）可以重用同一套
 * ceremony 資料組裝邏輯，不需要重複實作一份。
 */
public final class WebAuthnCeremonyFixtures {

    public static final SecureRandom RANDOM = new SecureRandom();

    private WebAuthnCeremonyFixtures() {
    }

    public static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    public static byte[] buildClientDataJson(ObjectMapper jsonMapper, String type, String challengeB64, String origin)
            throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("challenge", challengeB64);
        map.put("origin", origin);
        return jsonMapper.writeValueAsString(map).getBytes(StandardCharsets.UTF_8);
    }

    public static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        return keyPairGenerator.generateKeyPair();
    }

    /** 把真實 EC (P-256) 公鑰編碼為 COSE_Key（EC2, alg=ES256）CBOR bytes。 */
    public static byte[] buildEcCoseKeyBytes(ObjectMapper cborMapper, ECPublicKey publicKey) throws Exception {
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1, 2);   // kty: EC2
        cose.put(3, -7);  // alg: ES256
        cose.put(-1, 1);  // crv: P-256
        cose.put(-2, toFixedLength(publicKey.getW().getAffineX(), 32)); // x
        cose.put(-3, toFixedLength(publicKey.getW().getAffineY(), 32)); // y
        return cborMapper.writeValueAsBytes(cose);
    }

    public static byte[] toFixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] fixed = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, fixed, 0, length);
        } else {
            System.arraycopy(raw, 0, fixed, length - raw.length, raw.length);
        }
        return fixed;
    }

    public static byte[] signEcdsa(PrivateKey privateKey, byte[]... dataParts) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        for (byte[] part : dataParts) {
            signature.update(part);
        }
        return signature.sign();
    }

    public static byte[] buildAuthenticatorData(String rpId, byte flags, long signCount, byte[] aaguid,
                                                 byte[] credentialId, byte[] coseKeyBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)));
        out.write(flags);
        out.write(ByteBuffer.allocate(4).putInt((int) signCount).array());
        if ((flags & 0x40) != 0) {
            out.write(aaguid);
            out.write(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
            out.write(credentialId);
            out.write(coseKeyBytes);
        }
        return out.toByteArray();
    }

    public static byte[] buildAttestationObject(ObjectMapper cborMapper, String fmt, byte[] authenticatorData,
                                                 byte[] attStmtSig, X509Certificate leafCert) throws Exception {
        Map<String, Object> attStmt = new LinkedHashMap<>();
        attStmt.put("alg", -7);
        attStmt.put("sig", attStmtSig);
        attStmt.put("x5c", List.of(leafCert.getEncoded(), TestKeyAttestationFixtures.ROOT_CERTIFICATE.getEncoded()));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fmt", fmt);
        map.put("authData", authenticatorData);
        map.put("attStmt", attStmt);
        return cborMapper.writeValueAsBytes(map);
    }

    public static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
