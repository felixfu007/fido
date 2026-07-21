package com.fido.server.domain;

import com.fido.server.domain.enums.TenantStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * 對應 db-schema.md 表 `tenants`。購物網站租戶。
 */
public class Tenant {

    private Long tenantId;
    private UUID tenantUid = UUID.randomUUID();
    private String name;
    private String rpId;
    private String expectedOrigin;
    private byte[] apiKeyHash;
    private String apiKeyPrefix;
    private TenantStatus status = TenantStatus.ACTIVE;
    private int rateLimitTps = 100;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

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
        return apiKeyHash == null ? null : Arrays.copyOf(apiKeyHash, apiKeyHash.length);
    }

    public void setApiKeyHash(byte[] apiKeyHash) {
        this.apiKeyHash = apiKeyHash == null ? null : Arrays.copyOf(apiKeyHash, apiKeyHash.length);
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
