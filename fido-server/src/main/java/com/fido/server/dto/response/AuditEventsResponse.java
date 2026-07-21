package com.fido.server.dto.response;

import java.util.List;
import java.util.Map;

/** 對應 api-contract.md 5.2 response body（第一版可延後，本骨架已提供基本可用版本）。 */
public record AuditEventsResponse(List<AuditEvent> events, String nextCursor) {

    public record AuditEvent(String eventId, String type, String deviceId, String at, Map<String, Object> detail) {
    }
}
