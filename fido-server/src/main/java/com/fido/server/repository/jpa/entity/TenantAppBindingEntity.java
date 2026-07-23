package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.AppBindingRevokedReason;
import com.fido.server.domain.enums.RecordStatus;
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
 * JPA entity，對應 {@code docs/db-schema.md} 第 9 節 {@code dbo.tenant_app_bindings}（=
 * {@code infra/sql/002_create_tables.sql}）。見 {@link TenantEntity} 說明：與
 * {@link com.fido.server.domain.TenantAppBinding} 分開。
 */
@Entity
@Table(name = "tenant_app_bindings")
public class TenantAppBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "app_binding_pk")
    private Long appBindingPk;

    @Column(name = "binding_uid", nullable = false, unique = true)
    private UUID bindingUid;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "package_name", nullable = false, length = 255)
    private String packageName;

    @Column(name = "sha256_cert_fingerprint", nullable = false, length = 32)
    private byte[] sha256CertFingerprint;

    @Column(name = "apk_key_hash_origin", nullable = false, length = 120)
    private String apkKeyHashOrigin;

    @Column(name = "label", length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecordStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 50)
    private AppBindingRevokedReason revokedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getAppBindingPk() {
        return appBindingPk;
    }

    public void setAppBindingPk(Long appBindingPk) {
        this.appBindingPk = appBindingPk;
    }

    public UUID getBindingUid() {
        return bindingUid;
    }

    public void setBindingUid(UUID bindingUid) {
        this.bindingUid = bindingUid;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public byte[] getSha256CertFingerprint() {
        return sha256CertFingerprint;
    }

    public void setSha256CertFingerprint(byte[] sha256CertFingerprint) {
        this.sha256CertFingerprint = sha256CertFingerprint;
    }

    public String getApkKeyHashOrigin() {
        return apkKeyHashOrigin;
    }

    public void setApkKeyHashOrigin(String apkKeyHashOrigin) {
        this.apkKeyHashOrigin = apkKeyHashOrigin;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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

    public AppBindingRevokedReason getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(AppBindingRevokedReason revokedReason) {
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
}
