package com.fido.server.repository.jpa;

import com.fido.server.domain.AuthChallenge;
import com.fido.server.repository.AuthChallengeRepository;
import com.fido.server.repository.jpa.entity.AuthChallengeEntity;
import com.fido.server.repository.jpa.springdata.SpringDataAuthChallengeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@link AuthChallengeRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaAuthChallengeRepository implements AuthChallengeRepository {

    private final SpringDataAuthChallengeRepository delegate;

    public JpaAuthChallengeRepository(SpringDataAuthChallengeRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthChallenge> findByCeremonyId(String ceremonyId) {
        return delegate.findByCeremonyId(ceremonyId).map(JpaAuthChallengeRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthChallenge> findByChallengePk(Long challengePk) {
        return delegate.findById(challengePk).map(JpaAuthChallengeRepository::toDomain);
    }

    @Override
    @Transactional
    public AuthChallenge save(AuthChallenge challenge) {
        AuthChallengeEntity entity = toEntity(challenge);
        AuthChallengeEntity saved = delegate.save(entity);
        challenge.setChallengePk(saved.getChallengePk());
        return challenge;
    }

    private static AuthChallengeEntity toEntity(AuthChallenge challenge) {
        AuthChallengeEntity entity = new AuthChallengeEntity();
        entity.setChallengePk(challenge.getChallengePk());
        entity.setCeremonyId(challenge.getCeremonyId());
        entity.setTenantId(challenge.getTenantId());
        entity.setUserRefId(challenge.getUserRefId());
        entity.setChallenge(challenge.getChallenge());
        entity.setCeremonyType(challenge.getCeremonyType());
        entity.setStatus(challenge.getStatus());
        entity.setExpiresAt(challenge.getExpiresAt());
        entity.setConsumedAt(challenge.getConsumedAt());
        entity.setCreatedAt(challenge.getCreatedAt());
        return entity;
    }

    private static AuthChallenge toDomain(AuthChallengeEntity entity) {
        AuthChallenge challenge = new AuthChallenge();
        challenge.setChallengePk(entity.getChallengePk());
        challenge.setCeremonyId(entity.getCeremonyId());
        challenge.setTenantId(entity.getTenantId());
        challenge.setUserRefId(entity.getUserRefId());
        challenge.setChallenge(entity.getChallenge());
        challenge.setCeremonyType(entity.getCeremonyType());
        challenge.setStatus(entity.getStatus());
        challenge.setExpiresAt(entity.getExpiresAt());
        challenge.setConsumedAt(entity.getConsumedAt());
        challenge.setCreatedAt(entity.getCreatedAt());
        return challenge;
    }
}
