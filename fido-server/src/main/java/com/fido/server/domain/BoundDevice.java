package com.fido.server.domain;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;
import com.fido.server.domain.enums.SecurityLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * 對應 db-schema.md 表 `bound_devices`。註冊憑證所在的實體裝置與硬體安全屬性。
 * v1 情境 A：與 `fido_credentials` 為 1:1（見 db-schema.md DB14）。
 */
public class BoundDevice {

    private Long devicePk;
    private UUID deviceId = UUID.randomUUID();
    private Long credentialPk;
    private Long userRefId;
    private Long tenantId;
    private String deviceName;
    private String model;
    private String osVersion;
    private SecurityLevel securityLevel;
    private String attestationSummary;
    private RecordStatus status = RecordStatus.ACTIVE;
    private Instant revokedAt;
    private RevokedReason revokedReason;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
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
