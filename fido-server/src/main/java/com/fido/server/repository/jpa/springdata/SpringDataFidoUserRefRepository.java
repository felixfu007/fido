package com.fido.server.repository.jpa.springdata;

import com.fido.server.repository.jpa.entity.FidoUserRefEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaFidoUserRefRepository} 內部使用。
 */
public interface SpringDataFidoUserRefRepository extends JpaRepository<FidoUserRefEntity, Long> {

    Optional<FidoUserRefEntity> findByTenantIdAndExternalUserId(Long tenantId, String externalUserId);
}
