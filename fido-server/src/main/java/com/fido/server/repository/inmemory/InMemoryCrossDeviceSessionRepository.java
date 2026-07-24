package com.fido.server.repository.inmemory;

import com.fido.server.domain.CrossDeviceSession;
import com.fido.server.repository.CrossDeviceSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemoryCrossDeviceSessionRepository implements CrossDeviceSessionRepository {

    private final Map<String, CrossDeviceSession> byXdevId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public Optional<CrossDeviceSession> findByXdevId(String xdevId) {
        return Optional.ofNullable(byXdevId.get(xdevId));
    }

    @Override
    public synchronized CrossDeviceSession save(CrossDeviceSession session) {
        if (session.getXdevPk() == null) {
            session.setXdevPk(idSeq.getAndIncrement());
        }
        byXdevId.put(session.getXdevId(), session);
        return session;
    }
}
