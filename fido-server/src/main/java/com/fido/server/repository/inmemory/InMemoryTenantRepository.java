package com.fido.server.repository.inmemory;

import com.fido.server.domain.Tenant;
import com.fido.server.repository.TenantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory 骨架實作。僅供本機開發 / 測試，程序重啟即遺失所有資料。
 * 待 devops-engineer 建置 SQL Server 後，以 Spring Data JPA / JDBC 實作替換本類別。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemoryTenantRepository implements TenantRepository {

    private final Map<Long, Tenant> byId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized Optional<Tenant> findById(Long tenantId) {
        return Optional.ofNullable(byId.get(tenantId));
    }

    @Override
    public synchronized Optional<Tenant> findByTenantUid(UUID tenantUid) {
        return byId.values().stream().filter(t -> t.getTenantUid().equals(tenantUid)).findFirst();
    }

    @Override
    public synchronized Optional<Tenant> findByApiKeyHash(byte[] apiKeyHash) {
        return byId.values().stream()
                .filter(t -> Arrays.equals(t.getApiKeyHash(), apiKeyHash))
                .findFirst();
    }

    @Override
    public synchronized Optional<Tenant> findByRpId(String rpId) {
        return byId.values().stream().filter(t -> t.getRpId().equals(rpId)).findFirst();
    }

    @Override
    public synchronized Tenant save(Tenant tenant) {
        if (tenant.getTenantId() == null) {
            tenant.setTenantId(idSeq.getAndIncrement());
        }
        byId.put(tenant.getTenantId(), tenant);
        return tenant;
    }
}
