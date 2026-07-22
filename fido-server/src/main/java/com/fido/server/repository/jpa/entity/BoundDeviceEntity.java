package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;
import com.fido.server.domain.enums.SecurityLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity，對應 {@code docs/db-schema.md} 第 6 節 {@code dbo.bound_devices}。見
 * {@link TenantEntity} 說明：與 {@link com.fido.server.domain.BoundDevice} 分開。
 */
@Entity
@Table(name = "bound_devices")
public class BoundDeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_pk")
    private Long devicePk;

    @Column(name = "device_id", nullable = false, unique = true)
    private UUID deviceId;

    @Column(name = "credential_pk", nullable = false, unique = true)
    private Long credentialPk;

    @Column(name = "user_ref_id", nullable = false)
    private Long userRefId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "os_version", length = 50)
    private String osVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "security_level", nullable = false, length = 20)
    private SecurityLevel securityLevel;

    @Column(name = "attestation_summary", length = 1000)
    private String attestationSummary;

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

    public Long getDevicePk() {
        return devicePk;
    }

    public void setDevicePk(Long devicePk) {
        this.devicePk = devicePk;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

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

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public SecurityLevel getSecurityLevel() {
        return securityLevel;
    }

    public void setSecurityLevel(SecurityLevel securityLevel) {
        this.securityLevel = securityLevel;
    }

    public String getAttestationSummary() {
        return attestationSummary;
    }

    public void setAttestationSummary(String attestationSummary) {
        this.attestationSummary = attestationSummary;
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
