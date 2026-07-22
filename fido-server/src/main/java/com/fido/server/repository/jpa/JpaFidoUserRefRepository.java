package com.fido.server.repository.jpa;

import com.fido.server.domain.FidoUserRef;
import com.fido.server.repository.FidoUserRefRepository;
import com.fido.server.repository.jpa.entity.FidoUserRefEntity;
import com.fido.server.repository.jpa.springdata.SpringDataFidoUserRefRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@link FidoUserRefRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaFidoUserRefRepository implements FidoUserRefRepository {

    private final SpringDataFidoUserRefRepository delegate;

    public JpaFidoUserRefRepository(SpringDataFidoUserRefRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FidoUserRef> findById(Long userRefId) {
        return delegate.findById(userRefId).map(JpaFidoUserRefRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FidoUserRef> findByTenantIdAndExternalUserId(Long tenantId, String externalUserId) {
        return delegate.findByTenantIdAndExternalUserId(tenantId, externalUserId).map(JpaFidoUserRefRepository::toDomain);
    }

    @Override
    @Transactional
    public FidoUserRef save(FidoUserRef userRef) {
        FidoUserRefEntity entity = toEntity(userRef);
        FidoUserRefEntity saved = delegate.save(entity);
        userRef.setUserRefId(saved.getUserRefId());
        return userRef;
    }

    private static FidoUserRefEntity toEntity(FidoUserRef userRef) {
        FidoUserRefEntity entity = new FidoUserRefEntity();
        entity.setUserRefId(userRef.getUserRefId());
        entity.setTenantId(userRef.getTenantId());
        entity.setExternalUserId(userRef.getExternalUserId());
        entity.setUserHandle(userRef.getUserHandle());
        entity.setDisplayName(userRef.getDisplayName());
        entity.setCreatedAt(userRef.getCreatedAt());
        entity.setUpdatedAt(userRef.getUpdatedAt());
        return entity;
    }

    private static FidoUserRef toDomain(FidoUserRefEntity entity) {
        FidoUserRef userRef = new FidoUserRef();
        userRef.setUserRefId(entity.getUserRefId());
        userRef.setTenantId(entity.getTenantId());
        userRef.setExternalUserId(entity.getExternalUserId());
        userRef.setUserHandle(entity.getUserHandle());
        userRef.setDisplayName(entity.getDisplayName());
        userRef.setCreatedAt(entity.getCreatedAt());
        userRef.setUpdatedAt(entity.getUpdatedAt());
        return userRef;
    }
}
