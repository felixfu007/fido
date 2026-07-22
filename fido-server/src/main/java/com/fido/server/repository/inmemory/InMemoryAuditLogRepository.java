package com.fido.server.repository.inmemory;

import com.fido.server.domain.AuditLog;
import com.fido.server.repository.AuditLogRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory 骨架實作。不含正式版所需的 1 年保留 / 分割 / 清理排程
 * （devops-engineer 於真實 DB 依 db-schema.md 附錄建置）。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final Map<Long, AuditLog> byId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized AuditLog save(AuditLog auditLog) {
        if (auditLog.getAuditId() == null) {
            auditLog.setAuditId(idSeq.getAndIncrement());
        }
        byId.put(auditLog.getAuditId(), auditLog);
        return auditLog;
    }

    @Override
    public synchronized List<AuditLog> findByTenantIdAndUserRefId(Long tenantId, Long userRefId, int limit) {
        return byId.values().stream()
                .filter(a -> Objects.equals(a.getTenantId(), tenantId) && Objects.equals(a.getUserRefId(), userRefId))
                .sorted(Comparator.comparing(AuditLog::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}
