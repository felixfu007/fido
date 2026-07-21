package com.fido.server.domain;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;

import java.time.Instant;
import java.util.Arrays;

/**
 * 對應 db-schema.md 表 `fido_credentials`。WebAuthn 憑證（公鑰）。
 */
public class FidoCredential {

    private Long credentialPk;
    private Long userRefId;
    private Long tenantId;
    private byte[] credentialId;
    private byte[] credentialIdSha256;
    private byte[] publicKey;
    private int coseAlg;
    private long signCount;
    private byte[] aaguid;
    private String transports;
    private String attestationFormat;
    private RecordStatus status = RecordStatus.ACTIVE;
    private Instant revokedAt;
    private RevokedReason revokedReason;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
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
        return copy(credentialId);
    }

    public void setCredentialId(byte[] credentialId) {
        this.credentialId = copy(credentialId);
    }

    public byte[] getCredentialIdSha256() {
        return copy(credentialIdSha256);
    }

    public void setCredentialIdSha256(byte[] credentialIdSha256) {
        this.credentialIdSha256 = copy(credentialIdSha256);
    }

    public byte[] getPublicKey() {
        return copy(publicKey);
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = copy(publicKey);
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
        return copy(aaguid);
    }

    public void setAaguid(byte[] aaguid) {
        this.aaguid = copy(aaguid);
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

    private static byte[] copy(byte[] src) {
        return src == null ? null : Arrays.copyOf(src, src.length);
    }
}
