package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.CrossDeviceSessionStatus;
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
 * JPA entity，對應 {@code docs/db-schema.md} 第 11 節 {@code dbo.cross_device_sessions}
 * （= {@code infra/sql/002_create_tables.sql}，DB19）。見 {@link TenantEntity} 說明：與
 * {@link com.fido.server.domain.CrossDeviceSession}（純領域模型）分開。
 */
@Entity
@Table(name = "cross_device_sessions")
public class CrossDeviceSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "xdev_pk")
    private Long xdevPk;

    @Column(name = "xdev_id", nullable = false, unique = true, length = 64)
    private String xdevId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "challenge_pk", nullable = false, unique = true)
    private Long challengePk;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CrossDeviceSessionStatus status;

    @Column(name = "verification_code", nullable = false, length = 16)
    private String verificationCode;

    @Column(name = "desktop_ip", nullable = false, length = 45)
    private String desktopIp;

    @Column(name = "phone_ip", length = 45)
    private String phoneIp;

    @Column(name = "proximity_mismatch")
    private Boolean proximityMismatch;

    @Column(name = "user_ref_id")
    private Long userRefId;

    @Column(name = "credential_pk")
    private Long credentialPk;

    @Column(name = "issued_jti", length = 64)
    private String issuedJti;

    @Column(name = "issued_jwt", length = 4000)
    private String issuedJwt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
}
