package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;
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
 * JPA entity，對應 {@code docs/db-schema.md} 第 5 節 {@code dbo.fido_credentials}。見
 * {@link TenantEntity} 說明：與 {@link com.fido.server.domain.FidoCredential} 分開。
 *
 * <p>【DB4】{@code aaguid} 在權威 DDL 為 {@code BINARY(16)}（固定長度）；本 entity／H2 開發 schema
 * 改用等長 {@code VARBINARY(16)}，理由與已知落差見
 * {@code src/test/resources/db/h2/schema-h2.sql} 檔頭說明。
 */
@Entity
@Table(name = "fido_credentials")
public class FidoCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "credential_pk")
    private Long credentialPk;

    @Column(name = "user_ref_id", nullable = false)
    private Long userRefId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "credential_id", nullable = false, length = 1024)
    private byte[] credentialId;

    @Column(name = "credential_id_sha256", nullable = false, length = 32)
    private byte[] credentialIdSha256;

    @Column(name = "public_key", nullable = false, length = 512)
    private byte[] publicKey;

    @Column(name = "cose_alg", nullable = false)
    private int coseAlg;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "aaguid", length = 16)
    private byte[] aaguid;

    @Column(name = "transports", length = 100)
    private String transports;

    @Column(name = "attestation_format", length = 50)
    private String attestationFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecordStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 50)
    private RevokedReason revokedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public Long getCredentialPk() {
        return credentialPk;
    }

    public void setCredentialPk(Long credentialPk) {
        this.credentialPk = credentialPk;
    }

    public Long getUserRefId() {
        return userRefId;
    }

    public void setUserRefId(Long userRefId) {
        this.userRefId = userRefId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public byte[] getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(byte[] credentialId) {
        this.credentialId = credentialId;
    }

    public byte[] getCredentialIdSha256() {
        return credentialIdSha256;
    }

    public void setCredentialIdSha256(byte[] credentialIdSha256) {
        this.credentialIdSha256 = credentialIdSha256;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
    }

    public int getCoseAlg() {
        return coseAlg;
    }

    public void setCoseAlg(int coseAlg) {
        this.coseAlg = coseAlg;
    }

    public long getSignCount() {
        return signCount;
    }

    public void setSignCount(long signCount) {
        this.signCount = signCount;
    }

    public byte[] getAaguid() {
        return aaguid;
    }

    public void setAaguid(byte[] aaguid) {
        this.aaguid = aaguid;
    }

    public String getTransports() {
        return transports;
    }

    public void setTransports(String transports) {
        this.transports = transports;
    }

    public String getAttestationFormat() {
        return attestationFormat;
    }

    public void setAttestationFormat(String attestationFormat) {
        this.attestationFormat = attestationFormat;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public void setStatus(RecordStatus status) {
        this.status = status;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public RevokedReason getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(RevokedReason revokedReason) {
        this.revokedReason = revokedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
