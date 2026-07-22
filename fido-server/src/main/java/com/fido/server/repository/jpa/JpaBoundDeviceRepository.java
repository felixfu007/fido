package com.fido.server.repository.jpa;

import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.jpa.entity.BoundDeviceEntity;
import com.fido.server.repository.jpa.springdata.SpringDataBoundDeviceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link BoundDeviceRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaBoundDeviceRepository implements BoundDeviceRepository {

    private final SpringDataBoundDeviceRepository delegate;

    public JpaBoundDeviceRepository(SpringDataBoundDeviceRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BoundDevice> findByDeviceId(UUID deviceId) {
        return delegate.findByDeviceId(deviceId).map(JpaBoundDeviceRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BoundDevice> findByCredentialPk(Long credentialPk) {
        return delegate.findByCredentialPk(credentialPk).map(JpaBoundDeviceRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BoundDevice> findByUserRefId(Long userRefId) {
        return delegate.findByUserRefId(userRefId).stream()
                .map(JpaBoundDeviceRepository::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BoundDevice> findByUserRefIdAndStatus(Long userRefId, RecordStatus status) {
        return delegate.findByUserRefIdAndStatus(userRefId, status).stream()
                .map(JpaBoundDeviceRepository::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public BoundDevice save(BoundDevice device) {
        BoundDeviceEntity entity = toEntity(device);
        BoundDeviceEntity saved = delegate.save(entity);
        device.setDevicePk(saved.getDevicePk());
        return device;
    }

    private static BoundDeviceEntity toEntity(BoundDevice device) {
        BoundDeviceEntity entity = new BoundDeviceEntity();
        entity.setDevicePk(device.getDevicePk());
        entity.setDeviceId(device.getDeviceId());
        entity.setCredentialPk(device.getCredentialPk());
        entity.setUserRefId(device.getUserRefId());
        entity.setTenantId(device.getTenantId());
        entity.setDeviceName(device.getDeviceName());
        entity.setModel(device.getModel());
        entity.setOsVersion(device.getOsVersion());
        entity.setSecurityLevel(device.getSecurityLevel());
        entity.setAttestationSummary(device.getAttestationSummary());
        entity.setStatus(device.getStatus());
        entity.setRevokedAt(device.getRevokedAt());
        entity.setRevokedReason(device.getRevokedReason());
        entity.setCreatedAt(device.getCreatedAt());
        entity.setUpdatedAt(device.getUpdatedAt());
        entity.setLastUsedAt(device.getLastUsedAt());
        return entity;
    }

    private static BoundDevice toDomain(BoundDeviceEntity entity) {
        BoundDevice device = new BoundDevice();
        device.setDevicePk(entity.getDevicePk());
        device.setDeviceId(entity.getDeviceId());
        device.setCredentialPk(entity.getCredentialPk());
        device.setUserRefId(entity.getUserRefId());
        device.setTenantId(entity.getTenantId());
        device.setDeviceName(entity.getDeviceName());
        device.setModel(entity.getModel());
        device.setOsVersion(entity.getOsVersion());
        device.setSecurityLevel(entity.getSecurityLevel());
        device.setAttestationSummary(entity.getAttestationSummary());
        device.setStatus(entity.getStatus());
        device.setRevokedAt(entity.getRevokedAt());
        device.setRevokedReason(entity.getRevokedReason());
        device.setCreatedAt(entity.getCreatedAt());
        device.setUpdatedAt(entity.getUpdatedAt());
        device.setLastUsedAt(entity.getLastUsedAt());
        return device;
    }
}
