package com.fido.server.repository;

import com.fido.server.domain.AuditLog;

import java.util.List;

/**
 * `audit_log` 表的 persistence 介面。目前實作見
 * {@code com.fido.server.repository.inmemory.InMemoryAuditLogRepository}（僅供骨架測試，
 * 無 1 年保留 / 分割 / 清理排程，正式版由 devops-engineer 依 db-schema.md 附錄建置）。
 */
public interface AuditLogRepository {

    AuditLog save(AuditLog auditLog);

    /**
     * 供 5.2 稽核事件查詢使用；僅回傳指定租戶 + 使用者的事件，依 createdAt 由新到舊排序。
     */
    List<AuditLog> findByTenantIdAndUserRefId(Long tenantId, Long userRefId, int limit);
}
