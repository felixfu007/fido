package com.fido.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.context.RequestContext;
import com.fido.server.domain.AuditLog;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 【真實實作】`audit_log` 寫入輔助。每個端點依 api-contract.md 對應段落寫入事件。
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public void record(Long tenantId, Long userRefId, Long devicePk, String eventType,
                        AuditOutcome outcome, Map<String, Object> detail) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setUserRefId(userRefId);
        log.setDevicePk(devicePk);
        log.setEventType(eventType);
        log.setOutcome(outcome);
        log.setRequestId(RequestContext.getRequestId());
        log.setDetail(toJson(detail));
        auditLogRepository.save(log);
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }
}
