package com.fido.server.webauthn;

/**
 * COSE 演算法 ID（IANA COSE Algorithms registry）與 JCA 簽章演算法名稱的對照表。
 *
 * <p>優先支援 Android Keystore 平台驗證器最常見輸出：EC2/ES256。RSA 系列一併支援，
 * 涵蓋以 RSA 金鑰註冊的情境。WebAuthn 傳輸的 ECDSA 簽章固定為 DER（ASN.1 SEQUENCE of
 * r,s）編碼，與 {@code SHA*withECDSA} JCA 演算法預期的輸入格式一致，不需另行轉碼。
 */
public final class CoseAlgorithms {

    private CoseAlgorithms() {
    }

    public static final int ES256 = -7;   // ECDSA w/ SHA-256, P-256
    public static final int ES384 = -35;  // ECDSA w/ SHA-384, P-384
    public static final int ES512 = -36;  // ECDSA w/ SHA-512, P-521
    public static final int RS256 = -257; // RSASSA-PKCS1-v1_5 w/ SHA-256
    public static final int RS384 = -258; // RSASSA-PKCS1-v1_5 w/ SHA-384
    public static final int RS512 = -259; // RSASSA-PKCS1-v1_5 w/ SHA-512

    /**
     * @param coseAlg COSE alg 整數 ID（例如 credential 註冊時記錄的 coseAlg，或 attStmt.alg）
     * @return 對應的 JCA {@link java.security.Signature} 演算法名稱
     * @throws WebAuthnValidationException 不支援的演算法 ID
     */
    public static String toJcaSignatureAlgorithm(int coseAlg) {
        return switch (coseAlg) {
            case ES256 -> "SHA256withECDSA";
            case ES384 -> "SHA384withECDSA";
            case ES512 -> "SHA512withECDSA";
            case RS256 -> "SHA256withRSA";
            case RS384 -> "SHA384withRSA";
            case RS512 -> "SHA512withRSA";
            default -> throw new WebAuthnValidationException("不支援的 COSE 簽章演算法: " + coseAlg);
        };
    }
}
