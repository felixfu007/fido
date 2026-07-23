package com.fido.server.repository.jpa.springdata;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.jpa.entity.TenantAppBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaTenantAppBindingRepository} 內部使用。
 */
public interface SpringDataTenantAppBindingRepository extends JpaRepository<TenantAppBindingEntity, Long> {

    List<TenantAppBindingEntity> findByTenantIdAndStatus(Long tenantId, RecordStatus status);

    Optional<TenantAppBindingEntity> findByBindingUid(UUID bindingUid);
}
