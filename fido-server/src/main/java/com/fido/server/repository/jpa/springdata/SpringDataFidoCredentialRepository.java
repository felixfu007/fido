package com.fido.server.repository.jpa.springdata;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.jpa.entity.FidoCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaFidoCredentialRepository} 內部使用。
 */
public interface SpringDataFidoCredentialRepository extends JpaRepository<FidoCredentialEntity, Long> {

    Optional<FidoCredentialEntity> findByTenantIdAndCredentialIdSha256(Long tenantId, byte[] credentialIdSha256);

    List<FidoCredentialEntity> findByUserRefId(Long userRefId);

    List<FidoCredentialEntity> findByUserRefIdAndStatus(Long userRefId, RecordStatus status);

    long countByUserRefIdAndStatus(Long userRefId, RecordStatus status);
}
