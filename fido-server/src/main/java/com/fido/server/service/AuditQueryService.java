package com.fido.server.service;

import com.fido.server.domain.AuditLog;
import com.fido.server.domain.FidoUserRef;
import com.fido.server.domain.Tenant;
import com.fido.server.dto.response.AuditEventsResponse;
import com.fido.server.dto.response.AuditEventsResponse.AuditEvent;
import com.fido.server.repository.AuditLogRepository;
import com.fido.server.repository.FidoUserRefRepository;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 對應 api-contract.md 5.2（D11：第一版可延後實作，本骨架先提供基本可用版本，
 * 供客服在「帳號救援以客服人工為後盾」時查閱使用者的 FIDO 操作歷程）。
 *
 * <p>已實作：依租戶 + externalUserId 過濾、limit 上限、依時間新到舊排序、D7 防列舉
 * （使用者不存在回 200 空清單）。<b>尚未實作</b>：{@code from}/{@code to}/{@code type}
 * 篩選與 cursor 游標分頁（見 api-contract.md 5.2 Query 參數），屬 D11 標註的延後範圍。
 */
@Service
public class AuditQueryService {

    private final FidoUserRefRepository fidoUserRefRepository;
    private final AuditLogRepository auditLogRepository;

    public AuditQueryService(FidoUserRefRepository fidoUserRefRepository, AuditLogRepository auditLogRepository) {
        this.fidoUserRefRepository = fidoUserRefRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public AuditEventsResponse listEvents(Tenant tenant, String externalUserId, int limit) {
        Optional<FidoUserRef> userRefOpt = fidoUserRefRepository
                .findByTenantIdAndExternalUserId(tenant.getTenantId(), externalUserId);
        if (userRefOpt.isEmpty()) {
            return new AuditEventsResponse(List.of(), null);
        }
        List<AuditLog> logs = auditLogRepository.findByTenantIdAndUserRefId(
                tenant.getTenantId(), userRefOpt.get().getUserRefId(), Math.max(1, Math.min(limit, 100)));

        List<AuditEvent> events = logs.stream()
                .map(l -> new AuditEvent(
                        "ev_" + l.getAuditId(),
                        l.getEventType(),
                        l.getDevicePk() != null ? String.valueOf(l.getDevicePk()) : null,
                        DateTimeFormatter.ISO_INSTANT.format(l.getCreatedAt()),
                        Map.of()))
                .toList();

        return new AuditEventsResponse(events, null);
    }
}
