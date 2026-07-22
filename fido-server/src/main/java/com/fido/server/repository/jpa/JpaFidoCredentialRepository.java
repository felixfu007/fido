package com.fido.server.repository.jpa;

import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.jpa.entity.FidoCredentialEntity;
import com.fido.server.repository.jpa.springdata.SpringDataFidoCredentialRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link FidoCredentialRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaFidoCredentialRepository implements FidoCredentialRepository {

    private final SpringDataFidoCredentialRepository delegate;

    public JpaFidoCredentialRepository(SpringDataFidoCredentialRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FidoCredential> findByCredentialPk(Long credentialPk) {
        return delegate.findById(credentialPk).map(JpaFidoCredentialRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FidoCredential> findByTenantIdAndCredentialIdSha256(Long tenantId, byte[] credentialIdSha256) {
        return delegate.findByTenantIdAndCredentialIdSha256(tenantId, credentialIdSha256)
                .map(JpaFidoCredentialRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FidoCredential> findByUserRefId(Long userRefId) {
        return delegate.findByUserRefId(userRefId).stream()
                .map(JpaFidoCredentialRepository::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FidoCredential> findByUserRefIdAndStatus(Long userRefId, RecordStatus status) {
        return delegate.findByUserRefIdAndStatus(userRefId, status).stream()
                .map(JpaFidoCredentialRepository::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUserRefIdAndStatus(Long userRefId, RecordStatus status) {
        return delegate.countByUserRefIdAndStatus(userRefId, status);
    }

    @Override
    @Transactional
    public FidoCredential save(FidoCredential credential) {
        FidoCredentialEntity entity = toEntity(credential);
        FidoCredentialEntity saved = delegate.save(entity);
        credential.setCredentialPk(saved.getCredentialPk());
        return credential;
    }

    private static FidoCredentialEntity toEntity(FidoCredential credential) {
        FidoCredentialEntity entity = new FidoCredentialEntity();
        entity.setCredentialPk(credential.getCredentialPk());
        entity.setUserRefId(credential.getUserRefId());
        entity.setTenantId(credential.getTenantId());
        entity.setCredentialId(credential.getCredentialId());
        entity.setCredentialIdSha256(credential.getCredentialIdSha256());
        entity.setPublicKey(credential.getPublicKey());
        entity.setCoseAlg(credential.getCoseAlg());
        entity.setSignCount(credential.getSignCount());
        entity.setAaguid(credential.getAaguid());
        entity.setTransports(credential.getTransports());
        entity.setAttestationFormat(credential.getAttestationFormat());
        entity.setStatus(credential.getStatus());
        entity.setRevokedAt(credential.getRevokedAt());
        entity.setRevokedReason(credential.getRevokedReason());
        entity.setCreatedAt(credential.getCreatedAt());
        entity.setUpdatedAt(credential.getUpdatedAt());
        entity.setLastUsedAt(credential.getLastUsedAt());
        return entity;
    }

    private static FidoCredential toDomain(FidoCredentialEntity entity) {
        FidoCredential credential = new FidoCredential();
        credential.setCredentialPk(entity.getCredentialPk());
        credential.setUserRefId(entity.getUserRefId());
        credential.setTenantId(entity.getTenantId());
        credential.setCredentialId(entity.getCredentialId());
        credential.setCredentialIdSha256(entity.getCredentialIdSha256());
        credential.setPublicKey(entity.getPublicKey());
        credential.setCoseAlg(entity.getCoseAlg());
        credential.setSignCount(entity.getSignCount());
        credential.setAaguid(entity.getAaguid());
        credential.setTransports(entity.getTransports());
        credential.setAttestationFormat(entity.getAttestationFormat());
        credential.setStatus(entity.getStatus());
        credential.setRevokedAt(entity.getRevokedAt());
        credential.setRevokedReason(entity.getRevokedReason());
        credential.setCreatedAt(entity.getCreatedAt());
        credential.setUpdatedAt(entity.getUpdatedAt());
        credential.setLastUsedAt(entity.getLastUsedAt());
        return credential;
    }
}
