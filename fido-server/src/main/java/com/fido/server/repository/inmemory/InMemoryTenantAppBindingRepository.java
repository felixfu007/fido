package com.fido.server.repository.inmemory;

import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.TenantAppBindingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemoryTenantAppBindingRepository implements TenantAppBindingRepository {

    private final Map<Long, TenantAppBinding> byPk = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized List<TenantAppBinding> findByTenantIdAndStatus(Long tenantId, RecordStatus status) {
        return byPk.values().stream()
                .filter(b -> b.getTenantId().equals(tenantId) && b.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<TenantAppBinding> findByBindingUid(UUID bindingUid) {
        return byPk.values().stream().filter(b -> b.getBindingUid().equals(bindingUid)).findFirst();
    }

    @Override
    public synchronized TenantAppBinding save(TenantAppBinding binding) {
        if (binding.getAppBindingPk() == null) {
            binding.setAppBindingPk(idSeq.getAndIncrement());
        }
        byPk.put(binding.getAppBindingPk(), binding);
        return binding;
    }
}
