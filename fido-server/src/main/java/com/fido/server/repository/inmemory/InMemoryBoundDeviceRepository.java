package com.fido.server.repository.inmemory;

import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.BoundDeviceRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。 */
@Repository
public class InMemoryBoundDeviceRepository implements BoundDeviceRepository {

    private final Map<Long, BoundDevice> byPk = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized Optional<BoundDevice> findByDeviceId(UUID deviceId) {
        return byPk.values().stream().filter(d -> d.getDeviceId().equals(deviceId)).findFirst();
    }

    @Override
    public synchronized Optional<BoundDevice> findByCredentialPk(Long credentialPk) {
        return byPk.values().stream().filter(d -> d.getCredentialPk().equals(credentialPk)).findFirst();
    }

    @Override
    public synchronized List<BoundDevice> findByUserRefId(Long userRefId) {
        return byPk.values().stream()
                .filter(d -> d.getUserRefId().equals(userRefId))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized List<BoundDevice> findByUserRefIdAndStatus(Long userRefId, RecordStatus status) {
        return byPk.values().stream()
                .filter(d -> d.getUserRefId().equals(userRefId) && d.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized BoundDevice save(BoundDevice device) {
        if (device.getDevicePk() == null) {
            device.setDevicePk(idSeq.getAndIncrement());
        }
        byPk.put(device.getDevicePk(), device);
        return device;
    }
}
