package com.fido.server.controller;

import com.fido.server.context.TenantContext;
import com.fido.server.dto.response.AuditEventsResponse;
import com.fido.server.dto.response.FidoStatusResponse;
import com.fido.server.service.AuditQueryService;
import com.fido.server.service.UserStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 對應 api-contract.md 第 5 節：查詢類 API（5.1 綁定狀態、5.2 稽核事件）。 */
@RestController
@RequestMapping("/api/v1/users/{externalUserId}")
public class UserStatusController {

    private final UserStatusService userStatusService;
    private final AuditQueryService auditQueryService;

    public UserStatusController(UserStatusService userStatusService, AuditQueryService auditQueryService) {
        this.userStatusService = userStatusService;
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/fido-status")
    public FidoStatusResponse fidoStatus(@PathVariable String externalUserId) {
        return userStatusService.getStatus(TenantContext.require(), externalUserId);
    }

    @GetMapping("/audit-events")
    public AuditEventsResponse auditEvents(@PathVariable String externalUserId,
                                            @RequestParam(defaultValue = "50") int limit) {
        return auditQueryService.listEvents(TenantContext.require(), externalUserId, limit);
    }
}
