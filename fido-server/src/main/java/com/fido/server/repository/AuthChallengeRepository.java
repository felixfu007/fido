package com.fido.server.repository;

import com.fido.server.domain.AuthChallenge;

import java.util.Optional;

/**
 * `auth_challenges` 表的 persistence 介面。目前實作見
 * {@code com.fido.server.repository.inmemory.InMemoryAuthChallengeRepository}。
 *
 * <p>注意：正式 DB 版本應依 db-schema.md 附錄「資料保留與清理排程」加上定期清理過期列的
 * SQL Agent Job（devops-engineer 負責），此介面本身不需變動。
 */
public interface AuthChallengeRepository {

    Optional<AuthChallenge> findByCeremonyId(String ceremonyId);

    /**
     * 依內部 PK 反查（供跨裝置 QR 登入 {@code cross_device_sessions.challenge_pk} 1:1 外鍵
     * join 回 {@code auth_challenges} 使用，見 {@code CrossDeviceLoginService}）。
     */
    Optional<AuthChallenge> findByChallengePk(Long challengePk);

    AuthChallenge save(AuthChallenge challenge);
}
