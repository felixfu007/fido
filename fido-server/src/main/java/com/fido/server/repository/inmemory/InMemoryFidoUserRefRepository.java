package com.fido.server.repository.inmemory;

import com.fido.server.domain.FidoUserRef;
import com.fido.server.repository.FidoUserRefRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。 */
@Repository
public class InMemoryFidoUserRefRepository implements FidoUserRefRepository {

    private final Map<Long, FidoUserRef> byId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized Optional<FidoUserRef> findById(Long userRefId) {
        return Optional.ofNullable(byId.get(userRefId));
    }

    @Override
    public synchronized Optional<FidoUserRef> findByTenantIdAndExternalUserId(Long tenantId, String externalUserId) {
        return byId.values().stream()
                .filter(u -> u.getTenantId().equals(tenantId) && u.getExternalUserId().equals(externalUserId))
                .findFirst();
    }

    @Override
    public synchronized FidoUserRef save(FidoUserRef userRef) {
        if (userRef.getUserRefId() == null) {
            userRef.setUserRefId(idSeq.getAndIncrement());
        }
        byId.put(userRef.getUserRefId(), userRef);
        return userRef;
    }
}
