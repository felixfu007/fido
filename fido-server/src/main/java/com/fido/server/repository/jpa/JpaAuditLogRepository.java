package com.fido.server.repository.jpa;

import com.fido.server.domain.AuditLog;
import com.fido.server.repository.AuditLogRepository;
import com.fido.server.repository.jpa.entity.AuditLogEntity;
import com.fido.server.repository.jpa.springdata.SpringDataAuditLogRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link AuditLogRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaAuditLogRepository implements AuditLogRepository {

    private final SpringDataAuditLogRepository delegate;

    public JpaAuditLogRepository(SpringDataAuditLogRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional
    public AuditLog save(AuditLog auditLog) {
        AuditLogEntity entity = toEntity(auditLog);
        AuditLogEntity saved = delegate.save(entity);
        auditLog.setAuditId(saved.getAuditId());
        return auditLog;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLog> findByTenantIdAndUserRefId(Long tenantId, Long userRefId, int limit) {
        return delegate.findByTenantIdAndUserRefIdOrderByCreatedAtDesc(tenantId, userRefId, PageRequest.of(0, limit))
                .stream()
                .map(JpaAuditLogRepository::toDomain)
                .collect(Collectors.toList());
    }

    private static AuditLogEntity toEntity(AuditLog auditLog) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setAuditId(auditLog.getAuditId());
        entity.setTenantId(auditLog.getTenantId());
        entity.setUserRefId(auditLog.getUserRefId());
        entity.setDevicePk(auditLog.getDevicePk());
        entity.setEventType(auditLog.getEventType());
        entity.setOutcome(auditLog.getOutcome());
        entity.setRequestId(auditLog.getRequestId());
        entity.setIpAddress(auditLog.getIpAddress());
        entity.setDetail(auditLog.getDetail());
        entity.setCreatedAt(auditLog.getCreatedAt());
        return entity;
    }

    private static AuditLog toDomain(AuditLogEntity entity) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId(entity.getAuditId());
        auditLog.setTenantId(entity.getTenantId());
        auditLog.setUserRefId(entity.getUserRefId());
        auditLog.setDevicePk(entity.getDevicePk());
        auditLog.setEventType(entity.getEventType());
        auditLog.setOutcome(entity.getOutcome());
        auditLog.setRequestId(entity.getRequestId());
        auditLog.setIpAddress(entity.getIpAddress());
        auditLog.setDetail(entity.getDetail());
        auditLog.setCreatedAt(entity.getCreatedAt());
        return auditLog;
    }
}
