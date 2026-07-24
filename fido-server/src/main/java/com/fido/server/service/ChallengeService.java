package com.fido.server.service;

import com.fido.server.config.FidoProperties;
import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.CeremonyType;
import com.fido.server.domain.enums.ChallengeStatus;
import com.fido.server.exception.ApiException;
import com.fido.server.exception.ErrorCode;
import com.fido.server.repository.AuthChallengeRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 【真實實作】Challenge 產生、60 秒時效檢核與一次性消費邏輯，共用於註冊 / 登入兩種 ceremony。
 * 對齊 CLAUDE.md「Challenge 時效 60 秒，逾時前端自動重新申請」與 db-schema.md `auth_challenges`。
 */
@Service
public class ChallengeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthChallengeRepository authChallengeRepository;
    private final FidoProperties properties;

    public ChallengeService(AuthChallengeRepository authChallengeRepository, FidoProperties properties) {
        this.authChallengeRepository = authChallengeRepository;
        this.properties = properties;
    }

    public AuthChallenge create(Tenant tenant, Long userRefId, CeremonyType ceremonyType) {
        return create(tenant, userRefId, ceremonyType, properties.getChallenge().getTtlSeconds());
    }

    /**
     * 帶自訂 TTL 的版本，供跨裝置 QR 登入（情境三）使用：該 ceremony type 的 challenge TTL
     * 依 CLAUDE.md「Challenge 時效」段落 S6 拍板放寬為 120 秒，**僅此 ceremony type 偏離**
     * 60 秒預設，同裝置流程（呼叫 3 參數版本 {@link #create(Tenant, Long, CeremonyType)}）
     * 不受影響。見 {@code com.fido.server.service.CrossDeviceLoginService}。
     */
    public AuthChallenge create(Tenant tenant, Long userRefId, CeremonyType ceremonyType, int ttlSecondsOverride) {
        byte[] challengeBytes = new byte[32];
        RANDOM.nextBytes(challengeBytes);

        String prefix = ceremonyType == CeremonyType.REGISTRATION ? "reg_" : "auth_";
        String ceremonyId = prefix + UUID.randomUUID().toString().replace("-", "");

        AuthChallenge challenge = new AuthChallenge();
        challenge.setCeremonyId(ceremonyId);
        challenge.setTenantId(tenant.getTenantId());
        challenge.setUserRefId(userRefId);
        challenge.setChallenge(challengeBytes);
        challenge.setCeremonyType(ceremonyType);
        challenge.setStatus(ChallengeStatus.PENDING);
        Instant now = Instant.now();
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plusSeconds(ttlSecondsOverride));
        return authChallengeRepository.save(challenge);
    }

    /**
     * 依 ceremonyId 取出 challenge 並驗證：屬於同一租戶、ceremony 型別相符、狀態為 PENDING
     * 且未過期。全數通過才回傳；否則丟出對應 ApiException（CHALLENGE_NOT_FOUND /
     * CHALLENGE_EXPIRED），語意對齊 api-contract.md 2.2 步驟 1 / 3.2 步驟 1。
     */
    public AuthChallenge requireValid(String ceremonyId, Tenant tenant, CeremonyType expectedType) {
        Optional<AuthChallenge> found = authChallengeRepository.findByCeremonyId(ceremonyId);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.CHALLENGE_NOT_FOUND, "Ceremony not found.");
        }
        AuthChallenge challenge = found.get();
        // 跨租戶或跨 ceremony 型別一律視為「找不到」，避免資訊洩漏且行為單純。
        if (!challenge.getTenantId().equals(tenant.getTenantId()) || challenge.getCeremonyType() != expectedType) {
            throw new ApiException(ErrorCode.CHALLENGE_NOT_FOUND, "Ceremony not found.");
        }
        if (challenge.getStatus() != ChallengeStatus.PENDING || challenge.isExpired(Instant.now())) {
            if (challenge.getStatus() == ChallengeStatus.PENDING) {
                challenge.setStatus(ChallengeStatus.EXPIRED);
                authChallengeRepository.save(challenge);
            }
            throw new ApiException(ErrorCode.CHALLENGE_EXPIRED,
                    "The challenge has expired, please request a new one.");
        }
        return challenge;
    }

    public void consume(AuthChallenge challenge) {
        challenge.setStatus(ChallengeStatus.CONSUMED);
        challenge.setConsumedAt(Instant.now());
        authChallengeRepository.save(challenge);
    }
}
