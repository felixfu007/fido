package com.fido.server.service;

import com.fido.server.domain.SigningKey;
import com.fido.server.domain.enums.SigningKeyStatus;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 產生新的 EC P-256 signing key（{@link SigningKey}，狀態一律為 {@code ACTIVE}）。
 * 供 {@link JwtService}（全新資料庫首次啟動）與
 * {@code com.fido.server.admin.AdminCliRunner}（{@code rotate-signing-key} 手動輪替）共用，
 * 避免金鑰產生邏輯各自實作一份（見 db-schema.md 第 10 節 / DB18）。
 */
@Component
public class SigningKeyFactory {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter KID_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * @param preferredKid 若非空白，做為新金鑰的 {@code kid}（僅供「全新資料庫首次啟動」情境
     *                      使用，對應 {@code fido.session-jwt.kid} 設定值）；若為 {@code null}
     *                      或空白，自動產生 {@code sk_<yyyyMMdd>_<短亂數>} 格式。輪替
     *                      （{@code rotate-signing-key}）一律應傳入 {@code null}，因為沿用同一
     *                      個固定設定值會與既有（可能仍是 RETIRED 狀態）的舊 {@code kid} 撞
     *                      {@code UQ_signkey_kid} 唯一約束。
     */
    public SigningKey generate(String preferredKid) {
        KeyPair keyPair = generateEcKeyPair();
        String kid = (preferredKid != null && !preferredKid.isBlank()) ? preferredKid : generateDefaultKid();

        SigningKey key = new SigningKey();
        key.setKid(kid);
        key.setAlgorithm("ES256");
        key.setCurve("P-256");
        key.setPrivateKey(keyPair.getPrivate().getEncoded());
        key.setPublicKey(keyPair.getPublic().getEncoded());
        key.setStatus(SigningKeyStatus.ACTIVE);
        key.setCreatedAt(Instant.now());
        return key;
    }

    private static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("無法產生 EC P-256 金鑰對", e);
        }
    }

    private static String generateDefaultKid() {
        String datePart = KID_DATE_FORMAT.format(Instant.now());
        String randomPart = Long.toHexString(RANDOM.nextLong() & 0xFFFFFFL);
        while (randomPart.length() < 6) {
            randomPart = "0" + randomPart;
        }
        return "sk_" + datePart + "_" + randomPart;
    }
}
