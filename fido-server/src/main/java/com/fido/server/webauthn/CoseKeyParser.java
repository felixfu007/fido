package com.fido.server.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Map;

/**
 * 【真實實作】解析 COSE_Key（CBOR，整數標籤），取出 {@code alg}（label 3）等中繼資料欄位，
 * 並可將 EC2（kty=2）或 RSA（kty=3）COSE_Key 還原為 {@link java.security.PublicKey}，
 * 供 {@link AssertionSignatureVerifier} / {@link AttestationStatementVerifier} 的真實實作
 * 驗證簽章使用。
 *
 * <p>CBOR 整數 map key 經 Jackson 解為泛型 Map 時的鍵型別可能因函式庫版本而異（可能是
 * {@code Integer} 或字串化的 {@code "3"}），本類別以 {@link #field(Map, int)} 統一容錯查找。
 */
@Component
public class CoseKeyParser {

    private static final Logger log = LoggerFactory.getLogger(CoseKeyParser.class);

    /** COSE alg -7 = ES256，WebAuthn platform authenticator（含 Android Keystore）最常見演算法。 */
    public static final int DEFAULT_ALG_ES256 = -7;

    /** COSE kty：EC2（見 RFC 9053 §7.1）。 */
    public static final int COSE_KTY_EC2 = 2;
    /** COSE kty：RSA（見 RFC 9053 §8.4）。 */
    public static final int COSE_KTY_RSA = 3;

    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    @SuppressWarnings("unchecked")
    public int extractAlg(byte[] coseKeyBytes) {
        try {
            Map<Object, Object> map = cborMapper.readValue(coseKeyBytes, Map.class);
            Object alg = field(map, 3);
            if (alg instanceof Number number) {
                return number.intValue();
            }
            log.warn("COSE_Key 未找到可辨識的 alg 欄位，回退預設值 ES256(-7)");
        } catch (Exception e) {
            log.warn("COSE_Key 解析失敗，回退預設值 ES256(-7)：{}", e.getMessage());
        }
        return DEFAULT_ALG_ES256;
    }

    /**
     * 將 COSE_Key 原始 CBOR bytes 還原為 {@link PublicKey}。僅支援 EC2（kty=2，P-256/P-384/
     * P-521）與 RSA（kty=3）；其餘 kty（例如 OKP/Ed25519）目前非本專案範圍（Android Keystore
     * 平台驗證器不會產生），會拋出 {@link WebAuthnValidationException}。
     *
     * @throws WebAuthnValidationException COSE_Key 結構不合法、欄位缺失，或 kty 不支援
     */
    @SuppressWarnings("unchecked")
    public PublicKey toPublicKey(byte[] coseKeyBytes) {
        if (coseKeyBytes == null || coseKeyBytes.length == 0) {
            throw new WebAuthnValidationException("COSE_Key 為空");
        }
        Map<Object, Object> map;
        try {
            map = cborMapper.readValue(coseKeyBytes, Map.class);
        } catch (Exception e) {
            throw new WebAuthnValidationException("COSE_Key 非合法 CBOR：" + e.getMessage());
        }
        int kty = requireInt(map, 1, "kty");
        return switch (kty) {
            case COSE_KTY_EC2 -> toEcPublicKey(map);
            case COSE_KTY_RSA -> toRsaPublicKey(map);
            default -> throw new WebAuthnValidationException("不支援的 COSE kty: " + kty);
        };
    }

    private PublicKey toEcPublicKey(Map<Object, Object> map) {
        int crv = requireInt(map, -1, "crv");
        byte[] x = requireBytes(map, -2, "x");
        byte[] y = requireBytes(map, -3, "y");
        String curveName = switch (crv) {
            case 1 -> "secp256r1"; // P-256
            case 2 -> "secp384r1"; // P-384
            case 3 -> "secp521r1"; // P-521
            default -> throw new WebAuthnValidationException("不支援的 EC2 crv: " + crv);
        };
        try {
            AlgorithmParameters algParams = AlgorithmParameters.getInstance("EC");
            algParams.init(new ECGenParameterSpec(curveName));
            ECParameterSpec ecSpec = algParams.getParameterSpec(ECParameterSpec.class);
            ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
        } catch (GeneralSecurityException e) {
            throw new WebAuthnValidationException("EC2 公鑰還原失敗：" + e.getMessage());
        }
    }

    private PublicKey toRsaPublicKey(Map<Object, Object> map) {
        byte[] n = requireBytes(map, -1, "n");
        byte[] e = requireBytes(map, -2, "e");
        try {
            RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e));
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException ex) {
            throw new WebAuthnValidationException("RSA 公鑰還原失敗：" + ex.getMessage());
        }
    }

    private static int requireInt(Map<Object, Object> map, int label, String fieldName) {
        Object v = field(map, label);
        if (v instanceof Number number) {
            return number.intValue();
        }
        throw new WebAuthnValidationException("COSE_Key 缺少或型別錯誤欄位: " + fieldName);
    }

    private static byte[] requireBytes(Map<Object, Object> map, int label, String fieldName) {
        Object v = field(map, label);
        if (v instanceof byte[] bytes) {
            return bytes;
        }
        throw new WebAuthnValidationException("COSE_Key 缺少或型別錯誤欄位: " + fieldName);
    }

    /** 容錯查找：CBOR 整數 map key 依函式庫版本可能解為 {@code Integer} 或字串化數字。 */
    private static Object field(Map<Object, Object> map, int label) {
        Object v = map.get(label);
        if (v == null) {
            v = map.get(String.valueOf(label));
        }
        return v;
    }
}
