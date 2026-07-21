package com.fido.server.domain;

import com.fido.server.domain.enums.CeremonyType;
import com.fido.server.domain.enums.ChallengeStatus;

import java.time.Instant;
import java.util.Arrays;

/**
 * 對應 db-schema.md 表 `auth_challenges`。註冊 / 登入 ceremony 的 challenge，
 * 短生命週期（60 秒，見 CLAUDE.md）。`expires_at` 為伺服器端權威時效判斷依據。
 */
public class AuthChallenge {

    private Long challengePk;
    private String ceremonyId;
    private Long tenantId;
    private Long userRefId;
    private byte[] challenge;
    private CeremonyType ceremonyType;
    private ChallengeStatus status = ChallengeStatus.PENDING;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant createdAt = Instant.now();

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
        return challenge == null ? null : Arrays.copyOf(challenge, challenge.length);
    }

    public void setChallenge(byte[] challenge) {
        this.challenge = challenge == null ? null : Arrays.copyOf(challenge, challenge.length);
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

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
