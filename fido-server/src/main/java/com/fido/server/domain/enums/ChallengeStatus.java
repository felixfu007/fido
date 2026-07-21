package com.fido.server.domain.enums;

/** 對應 db-schema.md `auth_challenges.status` CHECK 約束。 */
public enum ChallengeStatus {
    PENDING,
    CONSUMED,
    EXPIRED
}
