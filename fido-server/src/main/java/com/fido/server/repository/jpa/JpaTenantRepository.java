package com.fido.server.repository.jpa;

import com.fido.server.domain.Tenant;
import com.fido.server.repository.TenantRepository;
import com.fido.server.repository.jpa.entity.TenantEntity;
import com.fido.server.repository.jpa.springdata.SpringDataTenantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link TenantRepository} 的 JPA 實作，底層委派 {@link SpringDataTenantRepository}，並在
 * {@link Tenant}（領域模型）與 {@link TenantEntity}（JPA entity）之間互轉。
 *
 * <p>僅在 {@code fido.persistence.mode=jpa}（預設）時建立為 Spring bean；見
 * {@code com.fido.server.repository.inmemory.InMemoryTenantRepository} 的
 * {@code fido.persistence.mode=memory} 對應版本。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaTenantRepository implements TenantRepository {

    private final SpringDataTenantRepository delegate;

    public JpaTenantRepository(SpringDataTenantRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findById(Long tenantId) {
        return delegate.findById(tenantId).map(JpaTenantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findByTenantUid(UUID tenantUid) {
        return delegate.findByTenantUid(tenantUid).map(JpaTenantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findByApiKeyHash(byte[] apiKeyHash) {
        return delegate.findByApiKeyHash(apiKeyHash).map(JpaTenantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findByRpId(String rpId) {
        return delegate.findByRpId(rpId).map(JpaTenantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tenant> findAll() {
        return delegate.findAll().stream().map(JpaTenantRepository::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public Tenant save(Tenant tenant) {
        TenantEntity entity = toEntity(tenant);
        TenantEntity saved = delegate.save(entity);
        tenant.setTenantId(saved.getTenantId());
        return tenant;
    }

    private static TenantEntity toEntity(Tenant tenant) {
        TenantEntity entity = new TenantEntity();
        entity.setTenantId(tenant.getTenantId());
        entity.setTenantUid(tenant.getTenantUid());
        entity.setName(tenant.getName());
        entity.setRpId(tenant.getRpId());
        entity.setExpectedOrigin(tenant.getExpectedOrigin());
        entity.setApiKeyHash(tenant.getApiKeyHash());
        entity.setApiKeyPrefix(tenant.getApiKeyPrefix());
        entity.setStatus(tenant.getStatus());
        entity.setRateLimitTps(tenant.getRateLimitTps());
        entity.setCreatedAt(tenant.getCreatedAt());
        entity.setUpdatedAt(tenant.getUpdatedAt());
        return entity;
    }

    private static Tenant toDomain(TenantEntity entity) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(entity.getTenantId());
        tenant.setTenantUid(entity.getTenantUid());
        tenant.setName(entity.getName());
        tenant.setRpId(entity.getRpId());
        tenant.setExpectedOrigin(entity.getExpectedOrigin());
        tenant.setApiKeyHash(entity.getApiKeyHash());
        tenant.setApiKeyPrefix(entity.getApiKeyPrefix());
        tenant.setStatus(entity.getStatus());
        tenant.setRateLimitTps(entity.getRateLimitTps());
        tenant.setCreatedAt(entity.getCreatedAt());
        tenant.setUpdatedAt(entity.getUpdatedAt());
        return tenant;
    }
}
