package com.fido.server.domain;

import com.fido.server.domain.enums.AppBindingRevokedReason;
import com.fido.server.domain.enums.RecordStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * 對應 db-schema.md 第 9 節 {@code tenant_app_bindings}。原生 App 情境（opt-in，
 * docs/origin-binding.md OB1/OB3）下，租戶授權「代表其網域發起 WebAuthn」的 Android App
 * 簽章指紋登錄。僅 opt-in 啟用原生 App 登入的租戶會有此表資料；純瀏覽器情境不需要任何列。
 *
 * <p>v1 無 REST 端點管理（origin-binding.md OB6：人工 onboarding），本類別目前僅由
 * {@link com.fido.server.service.OriginValidator} 讀取 ACTIVE 列做 origin 允許清單比對。
 */
public class TenantAppBinding {

    private Long appBindingPk;
    private UUID bindingUid = UUID.randomUUID();
    private Long tenantId;
    private String packageName;
    private byte[] sha256CertFingerprint;
    private String apkKeyHashOrigin;
    private String label;
    private RecordStatus status = RecordStatus.ACTIVE;
    private Instant revokedAt;
    private AppBindingRevokedReason revokedReason;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

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
        return sha256CertFingerprint == null ? null : Arrays.copyOf(sha256CertFingerprint, sha256CertFingerprint.length);
    }

    public void setSha256CertFingerprint(byte[] sha256CertFingerprint) {
        this.sha256CertFingerprint = sha256CertFingerprint == null
                ? null : Arrays.copyOf(sha256CertFingerprint, sha256CertFingerprint.length);
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
