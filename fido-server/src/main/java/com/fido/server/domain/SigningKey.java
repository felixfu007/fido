package com.fido.server.domain;

import com.fido.server.domain.enums.SigningKeyStatus;

import java.time.Instant;
import java.util.Arrays;

/**
 * 對應 db-schema.md 第 10 節 {@code signing_keys}（DB18）。Session JWT（ES256 / EC P-256）
 * 簽章金鑰的持久化保存，平台級資料（非租戶隔離、無 {@code tenant_id}）。
 *
 * <p>{@link #privateKey} 為 PKCS#8 DER、{@link #publicKey} 為 X.509 SubjectPublicKeyInfo DER，
 * 皆由 {@code JwtService} 以 {@code KeyFactory.getInstance("EC")} 還原成 {@link java.security.KeyPair}。
 * 私鑰保護沿用 TDE（見 db-schema.md），不加應用層封裝。
 */
public class SigningKey {

    private Long keyPk;
    private String kid;
    private String algorithm = "ES256";
    private String curve = "P-256";
    private byte[] privateKey;
    private byte[] publicKey;
    private SigningKeyStatus status = SigningKeyStatus.ACTIVE;
    private Instant createdAt = Instant.now();
    private Instant retiredAt;

    public Long getKeyPk() {
        return keyPk;
    }

    public void setKeyPk(Long keyPk) {
        this.keyPk = keyPk;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getCurve() {
        return curve;
    }

    public void setCurve(String curve) {
        this.curve = curve;
    }

    public byte[] getPrivateKey() {
        return privateKey == null ? null : Arrays.copyOf(privateKey, privateKey.length);
    }

    public void setPrivateKey(byte[] privateKey) {
        this.privateKey = privateKey == null ? null : Arrays.copyOf(privateKey, privateKey.length);
    }

    public byte[] getPublicKey() {
        return publicKey == null ? null : Arrays.copyOf(publicKey, publicKey.length);
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = publicKey == null ? null : Arrays.copyOf(publicKey, publicKey.length);
    }

    public SigningKeyStatus getStatus() {
        return status;
    }

    public void setStatus(SigningKeyStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(Instant retiredAt) {
        this.retiredAt = retiredAt;
    }
}
