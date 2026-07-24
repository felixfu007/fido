package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.SigningKeyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity，對應 {@code docs/db-schema.md} 第 10 節 {@code dbo.signing_keys}（=
 * {@code infra/sql/002_create_tables.sql}，DB18）。平台級資料，無 {@code tenant_id}/FK。
 */
@Entity
@Table(name = "signing_keys")
public class SigningKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "key_pk")
    private Long keyPk;

    @Column(name = "kid", nullable = false, unique = true, length = 64)
    private String kid;

    @Column(name = "algorithm", nullable = false, length = 20)
    private String algorithm;

    @Column(name = "curve", nullable = false, length = 20)
    private String curve;

    @Column(name = "private_key", nullable = false, length = 1024)
    private byte[] privateKey;

    @Column(name = "public_key", nullable = false, length = 512)
    private byte[] publicKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SigningKeyStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retired_at")
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
        return privateKey;
    }

    public void setPrivateKey(byte[] privateKey) {
        this.privateKey = privateKey;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
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
