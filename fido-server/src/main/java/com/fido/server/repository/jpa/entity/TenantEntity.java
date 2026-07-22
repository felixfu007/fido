package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.TenantStatus;
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
 * JPA entity，對應 {@code docs/db-schema.md} 第 3 節 {@code dbo.tenants}（= {@code infra/sql/002_create_tables.sql}）。
 *
 * <p>刻意與 {@link com.fido.server.domain.Tenant}（純領域模型，不依賴任何 persistence
 * framework）分開：本類別只在 {@code fido.persistence.mode=jpa} 時才會被
 * {@link com.fido.server.repository.jpa.JpaTenantRepository} 用來讀寫資料庫，並在該類別內
 * 與 {@code Tenant} 互轉，service/controller 層完全不感知本類別存在。
 */
@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "tenant_uid", nullable = false, unique = true)
    private UUID tenantUid;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "rp_id", nullable = false, length = 255, unique = true)
    private String rpId;

    @Column(name = "expected_origin", nullable = false, length = 512)
    private String expectedOrigin;

    @Column(name = "api_key_hash", nullable = false, length = 32, unique = true)
    private byte[] apiKeyHash;

    @Column(name = "api_key_prefix", nullable = false, length = 12)
    private String apiKeyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status;

    @Column(name = "rate_limit_tps", nullable = false)
    private int rateLimitTps;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getTenantUid() {
        return tenantUid;
    }

    public void setTenantUid(UUID tenantUid) {
        this.tenantUid = tenantUid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRpId() {
        return rpId;
    }

    public void setRpId(String rpId) {
        this.rpId = rpId;
    }

    public String getExpectedOrigin() {
        return expectedOrigin;
    }

    public void setExpectedOrigin(String expectedOrigin) {
        this.expectedOrigin = expectedOrigin;
    }

    public byte[] getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(byte[] apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    public int getRateLimitTps() {
        return rateLimitTps;
    }

    public void setRateLimitTps(int rateLimitTps) {
        this.rateLimitTps = rateLimitTps;
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
