package com.fido.server.repository.jpa.springdata;

import com.fido.server.repository.jpa.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA 底層介面，僅供 {@link com.fido.server.repository.jpa.JpaTenantRepository}
 * （對外實作 {@link com.fido.server.repository.TenantRepository}）內部使用。
 */
public interface SpringDataTenantRepository extends JpaRepository<TenantEntity, Long> {

    Optional<TenantEntity> findByTenantUid(UUID tenantUid);

    Optional<TenantEntity> findByApiKeyHash(byte[] apiKeyHash);

    Optional<TenantEntity> findByRpId(String rpId);
}
