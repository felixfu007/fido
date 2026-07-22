package com.fido.server.repository.jpa.entity;

import com.fido.server.domain.enums.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity，對應 {@code docs/db-schema.md} 第 8 節 {@code dbo.audit_log}。見
 * {@link TenantEntity} 說明：與 {@link com.fido.server.domain.AuditLog} 分開。
 *
 * <p>{@code detail} 權威型別為 {@code NVARCHAR(MAX)}；本 entity 以 {@link Lob} 對應（SQL Server
 * 方言下 Hibernate 會產生等效的大型文字型別，H2 開發 schema 對應 {@code CLOB}，見
 * {@code src/test/resources/db/h2/schema-h2.sql} 檔頭已知落差說明）。
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_ref_id")
    private Long userRefId;

    @Column(name = "device_pk")
    private Long devicePk;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Lob
    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getAuditId() {
        return auditId;
    }

    public void setAuditId(Long auditId) {
        this.auditId = auditId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserRefId() {
        return userRefId;
    }

    public void setUserRefId(Long userRefId) {
        this.userRefId = userRefId;
    }

    public Long getDevicePk() {
        return devicePk;
    }

    public void setDevicePk(Long devicePk) {
        this.devicePk = devicePk;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(AuditOutcome outcome) {
        this.outcome = outcome;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
