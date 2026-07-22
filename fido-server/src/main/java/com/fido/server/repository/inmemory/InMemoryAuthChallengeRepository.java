package com.fido.server.repository.inmemory;

import com.fido.server.domain.AuthChallenge;
import com.fido.server.repository.AuthChallengeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemoryAuthChallengeRepository implements AuthChallengeRepository {

    private final Map<String, AuthChallenge> byCeremonyId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public Optional<AuthChallenge> findByCeremonyId(String ceremonyId) {
        return Optional.ofNullable(byCeremonyId.get(ceremonyId));
    }

    @Override
    public synchronized AuthChallenge save(AuthChallenge challenge) {
        if (challenge.getChallengePk() == null) {
            challenge.setChallengePk(idSeq.getAndIncrement());
        }
        byCeremonyId.put(challenge.getCeremonyId(), challenge);
        return challenge;
    }
}
