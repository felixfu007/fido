package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.CeremonyType;
import com.fido.server.domain.enums.ChallengeStatus;
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
 * JPA entity，對應 {@code docs/db-schema.md} 第 7 節 {@code dbo.auth_challenges}。見
 * {@link TenantEntity} 說明：與 {@link com.fido.server.domain.AuthChallenge} 分開。
 */
@Entity
@Table(name = "auth_challenges")
public class AuthChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "challenge_pk")
    private Long challengePk;

    @Column(name = "ceremony_id", nullable = false, unique = true, length = 64)
    private String ceremonyId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_ref_id")
    private Long userRefId;

    @Column(name = "challenge", nullable = false, length = 64)
    private byte[] challenge;

    @Enumerated(EnumType.STRING)
    @Column(name = "ceremony_type", nullable = false, length = 20)
    private CeremonyType ceremonyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChallengeStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getChallengePk() {
        return challengePk;
    }

    public void setChallengePk(Long challengePk) {
        this.challengePk = challengePk;
    }

    public String getCeremonyId() {
        return ceremonyId;
    }

    public void setCeremonyId(String ceremonyId) {
        this.ceremonyId = ceremonyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserRefId() {
        return userRefId;
    }

    public void setUserRefId(Long userRefId) {
        this.userRefId = userRefId;
    }

    public byte[] getChallenge() {
        return challenge;
    }

    public void setChallenge(byte[] challenge) {
        this.challenge = challenge;
    }

    public CeremonyType getCeremonyType() {
        return ceremonyType;
    }

    public void setCeremonyType(CeremonyType ceremonyType) {
        this.ceremonyType = ceremonyType;
    }

    public ChallengeStatus getStatus() {
        return status;
    }

    public void setStatus(ChallengeStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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
}
