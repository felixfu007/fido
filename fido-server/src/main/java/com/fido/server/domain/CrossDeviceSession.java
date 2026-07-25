package com.fido.server.domain;

import com.fido.server.domain.enums.CrossDeviceSessionStatus;

import java.time.Instant;

/**
 * 對應 db-schema.md 第 11 節 {@code cross_device_sessions}（DB19）。情境三（跨裝置 QR
 * transaction confirmation）的登入 session，1:1 包住一列既有 {@code auth_challenges}
 * （{@link #challengePk}），讓 assertion 密碼學驗證能重用既有以 ceremony 為入口的邏輯
 * （見 {@code com.fido.server.service.CrossDeviceLoginService}）。
 */
public class CrossDeviceSession {

    private Long xdevPk;
    private String xdevId;
    private Long tenantId;
    private Long challengePk;
    private CrossDeviceSessionStatus status = CrossDeviceSessionStatus.PENDING;
    private String verificationCode;
    private String desktopIp;
    private String phoneIp;
    private Boolean proximityMismatch;
    private Long userRefId;
    private Long credentialPk;
    private String issuedJti;
    private String issuedJwt;
    private Instant expiresAt;
    private Instant scannedAt;
    private Instant confirmedAt;
    private Instant consumedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getXdevPk() {
        return xdevPk;
    }

    public void setXdevPk(Long xdevPk) {
        this.xdevPk = xdevPk;
    }

    public String getXdevId() {
        return xdevId;
    }

    public void setXdevId(String xdevId) {
        this.xdevId = xdevId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getChallengePk() {
        return challengePk;
    }

    public void setChallengePk(Long challengePk) {
        this.challengePk = challengePk;
    }

    public CrossDeviceSessionStatus getStatus() {
        return status;
    }

    public void setStatus(CrossDeviceSessionStatus status) {
        this.status = status;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    public String getDesktopIp() {
        return desktopIp;
    }

    public void setDesktopIp(String desktopIp) {
        this.desktopIp = desktopIp;
    }

    public String getPhoneIp() {
        return phoneIp;
    }

    public void setPhoneIp(String phoneIp) {
        this.phoneIp = phoneIp;
    }

    public Boolean getProximityMismatch() {
        return proximityMismatch;
    }

    public void setProximityMismatch(Boolean proximityMismatch) {
        this.proximityMismatch = proximityMismatch;
    }

    public Long getUserRefId() {
        return userRefId;
    }

    public void setUserRefId(Long userRefId) {
        this.userRefId = userRefId;
    }

    public Long getCredentialPk() {
        return credentialPk;
    }

    public void setCredentialPk(Long credentialPk) {
        this.credentialPk = credentialPk;
    }

    public String getIssuedJti() {
        return issuedJti;
    }

    public void setIssuedJti(String issuedJti) {
        this.issuedJti = issuedJti;
    }

    public String getIssuedJwt() {
        return issuedJwt;
    }

    /**
     * db-schema.md 第 11 節 DB20：端點 C 簽發、待端點 D 領取的完整 session JWT。只在
     * {@code CONFIRMED} 態有值；端點 D 以守衛式 UPDATE 領取並轉 {@code CONSUMED} 時一併清為
     * {@code null}（見 {@code CrossDeviceSessionRepository#consumeConfirmedJwt}）。
     */
    public void setIssuedJwt(String issuedJwt) {
        this.issuedJwt = issuedJwt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(Instant scannedAt) {
        this.scannedAt = scannedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
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

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
