package com.fido.server.repository.jpa.springdata;

import com.fido.server.repository.jpa.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaAuditLogRepository} 內部使用。
 *
 * <p>{@code tenantId}/{@code userRefId} 允許 {@code null}；Spring Data JPA 的衍生查詢對
 * {@code null} 參數會自動轉為 {@code IS NULL} 比對（對齊 db-schema.md「pre-auth 事件 tenant/user
 * 可為 NULL」）。
 */
public interface SpringDataAuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByTenantIdAndUserRefIdOrderByCreatedAtDesc(Long tenantId, Long userRefId, Pageable pageable);
}
