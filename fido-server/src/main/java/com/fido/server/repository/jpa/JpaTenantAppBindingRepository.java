package com.fido.server.repository.jpa;

import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.TenantAppBindingRepository;
import com.fido.server.repository.jpa.entity.TenantAppBindingEntity;
import com.fido.server.repository.jpa.springdata.SpringDataTenantAppBindingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link TenantAppBindingRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaTenantAppBindingRepository implements TenantAppBindingRepository {

    private final SpringDataTenantAppBindingRepository delegate;

    public JpaTenantAppBindingRepository(SpringDataTenantAppBindingRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantAppBinding> findByTenantIdAndStatus(Long tenantId, RecordStatus status) {
        return delegate.findByTenantIdAndStatus(tenantId, status).stream()
                .map(JpaTenantAppBindingRepository::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantAppBinding> findByBindingUid(UUID bindingUid) {
        return delegate.findByBindingUid(bindingUid).map(JpaTenantAppBindingRepository::toDomain);
    }

    @Override
    @Transactional
    public TenantAppBinding save(TenantAppBinding binding) {
        TenantAppBindingEntity entity = toEntity(binding);
        TenantAppBindingEntity saved = delegate.save(entity);
        binding.setAppBindingPk(saved.getAppBindingPk());
        return binding;
    }

    private static TenantAppBindingEntity toEntity(TenantAppBinding binding) {
        TenantAppBindingEntity entity = new TenantAppBindingEntity();
        entity.setAppBindingPk(binding.getAppBindingPk());
        entity.setBindingUid(binding.getBindingUid());
        entity.setTenantId(binding.getTenantId());
        entity.setPackageName(binding.getPackageName());
        entity.setSha256CertFingerprint(binding.getSha256CertFingerprint());
        entity.setApkKeyHashOrigin(binding.getApkKeyHashOrigin());
        entity.setLabel(binding.getLabel());
        entity.setStatus(binding.getStatus());
        entity.setRevokedAt(binding.getRevokedAt());
        entity.setRevokedReason(binding.getRevokedReason());
        entity.setCreatedAt(binding.getCreatedAt());
        entity.setUpdatedAt(binding.getUpdatedAt());
        return entity;
    }

    private static TenantAppBinding toDomain(TenantAppBindingEntity entity) {
        TenantAppBinding binding = new TenantAppBinding();
        binding.setAppBindingPk(entity.getAppBindingPk());
        binding.setBindingUid(entity.getBindingUid());
        binding.setTenantId(entity.getTenantId());
        binding.setPackageName(entity.getPackageName());
        binding.setSha256CertFingerprint(entity.getSha256CertFingerprint());
        binding.setApkKeyHashOrigin(entity.getApkKeyHashOrigin());
        binding.setLabel(entity.getLabel());
        binding.setStatus(entity.getStatus());
        binding.setRevokedAt(entity.getRevokedAt());
        binding.setRevokedReason(entity.getRevokedReason());
        binding.setCreatedAt(entity.getCreatedAt());
        binding.setUpdatedAt(entity.getUpdatedAt());
        return binding;
    }
}
