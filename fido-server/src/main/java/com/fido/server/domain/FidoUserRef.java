package com.fido.server.domain;

import java.time.Instant;
import java.util.Arrays;

/**
 * 對應 db-schema.md 表 `fido_user_ref`。購物網站使用者在 FIDO 伺服器的參照
 * （非身分來源，身分權威來源仍是購物網站既有帳密系統）。
 */
public class FidoUserRef {

    private Long userRefId;
    private Long tenantId;
    private String externalUserId;
    private byte[] userHandle;
    private String displayName;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

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

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }

    public byte[] getUserHandle() {
        return userHandle == null ? null : Arrays.copyOf(userHandle, userHandle.length);
    }

    public void setUserHandle(byte[] userHandle) {
        this.userHandle = userHandle == null ? null : Arrays.copyOf(userHandle, userHandle.length);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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
