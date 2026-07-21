package com.fido.server.service;

import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.FidoUserRef;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;
import com.fido.server.dto.response.DeviceListResponse;
import com.fido.server.dto.response.DeviceListResponse.DeviceSummary;
import com.fido.server.dto.response.DeviceRevokeResponse;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.FidoUserRefRepository;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 對應 api-contract.md 第 4 節：裝置管理 API。
 * 【真實實作】D7 防帳號/裝置列舉（使用者不存在回 200 空清單；撤銷一律回 200 冪等 no-op，
 * no-op 情況仍寫 audit_log 供事後鑑識）與 D10 軟刪除語意。
 */
@Service
public class DeviceService {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final FidoUserRefRepository fidoUserRefRepository;
    private final FidoCredentialRepository fidoCredentialRepository;
    private final BoundDeviceRepository boundDeviceRepository;
    private final AuditService auditService;

    public DeviceService(FidoUserRefRepository fidoUserRefRepository,
                          FidoCredentialRepository fidoCredentialRepository,
                          BoundDeviceRepository boundDeviceRepository,
                          AuditService auditService) {
        this.fidoUserRefRepository = fidoUserRefRepository;
        this.fidoCredentialRepository = fidoCredentialRepository;
        this.boundDeviceRepository = boundDeviceRepository;
        this.auditService = auditService;
    }

    public DeviceListResponse listDevices(Tenant tenant, String externalUserId, String statusFilter, int limit) {
        Optional<FidoUserRef> userRefOpt = fidoUserRefRepository
                .findByTenantIdAndExternalUserId(tenant.getTenantId(), externalUserId);
        if (userRefOpt.isEmpty()) {
            // D7 防列舉：使用者不存在 -> 200 + 空清單，不回 404。
            return new DeviceListResponse(externalUserId, List.of(), null);
        }
        FidoUserRef userRef = userRefOpt.get();

        List<BoundDevice> devices = boundDeviceRepository.findByUserRefId(userRef.getUserRefId());
        String normalizedFilter = statusFilter == null ? "ACTIVE" : statusFilter.toUpperCase();

        List<DeviceSummary> summaries = devices.stream()
                .filter(d -> matchesStatusFilter(d, normalizedFilter))
                .sorted(Comparator.comparing(BoundDevice::getCreatedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 100)))
                .map(d -> toSummary(d, findCredential(d)))
                .toList();

        return new DeviceListResponse(externalUserId, summaries, null);
    }

    public DeviceRevokeResponse revokeDevice(Tenant tenant, String externalUserId, String deviceIdRaw) {
        Instant now = Instant.now();
        UUID deviceId;
        try {
            deviceId = UUID.fromString(deviceIdRaw);
        } catch (IllegalArgumentException e) {
            // D7 冪等 no-op：格式不對也視為「找不到但無害」，回傳 REVOKED，不洩漏原因。
            auditService.record(tenant.getTenantId(), null, null, "DEVICE_REVOKE_NOOP", AuditOutcome.FAILURE,
                    Map.of("matched", false, "reason", "invalid deviceId format"));
            return new DeviceRevokeResponse(deviceIdRaw, "REVOKED", DateTimeFormatter.ISO_INSTANT.format(now));
        }

        Optional<FidoUserRef> userRefOpt = fidoUserRefRepository
                .findByTenantIdAndExternalUserId(tenant.getTenantId(), externalUserId);
        Optional<BoundDevice> deviceOpt = boundDeviceRepository.findByDeviceId(deviceId);

        boolean matched = userRefOpt.isPresent() && deviceOpt.isPresent()
                && deviceOpt.get().getTenantId().equals(tenant.getTenantId())
                && deviceOpt.get().getUserRefId().equals(userRefOpt.get().getUserRefId())
                && deviceOpt.get().getStatus() == RecordStatus.ACTIVE;

        if (!matched) {
            auditService.record(tenant.getTenantId(), userRefOpt.map(FidoUserRef::getUserRefId).orElse(null),
                    deviceOpt.map(BoundDevice::getDevicePk).orElse(null), "DEVICE_REVOKE_NOOP", AuditOutcome.FAILURE,
                    Map.of("matched", false));
            return new DeviceRevokeResponse(deviceIdRaw, "REVOKED", DateTimeFormatter.ISO_INSTANT.format(now));
        }

        BoundDevice device = deviceOpt.get();
        device.setStatus(RecordStatus.REVOKED);
        device.setRevokedAt(now);
        device.setRevokedReason(RevokedReason.USER_REQUEST);
        boundDeviceRepository.save(device);

        fidoCredentialRepository.findByCredentialPk(device.getCredentialPk()).ifPresent(credential -> {
            credential.setStatus(RecordStatus.REVOKED);
            credential.setRevokedAt(now);
            credential.setRevokedReason(RevokedReason.USER_REQUEST);
            fidoCredentialRepository.save(credential);
        });

        auditService.record(tenant.getTenantId(), userRefOpt.get().getUserRefId(), device.getDevicePk(),
                "DEVICE_REVOKED_BY_USER", AuditOutcome.SUCCESS, Map.of("matched", true));

        return new DeviceRevokeResponse(device.getDeviceId().toString(), "REVOKED",
                DateTimeFormatter.ISO_INSTANT.format(device.getRevokedAt()));
    }

    private boolean matchesStatusFilter(BoundDevice device, String filter) {
        return switch (filter) {
            case "ALL" -> true;
            case "REVOKED" -> device.getStatus() == RecordStatus.REVOKED;
            default -> device.getStatus() == RecordStatus.ACTIVE;
        };
    }

    private FidoCredential findCredential(BoundDevice device) {
        return fidoCredentialRepository.findByCredentialPk(device.getCredentialPk()).orElse(null);
    }

    private DeviceSummary toSummary(BoundDevice device, FidoCredential credential) {
        return new DeviceSummary(
                device.getDeviceId().toString(),
                device.getDeviceName(),
                device.getModel(),
                device.getOsVersion(),
                device.getSecurityLevel() != null ? device.getSecurityLevel().name() : null,
                credential != null && credential.getAaguid() != null ? aaguidToUuidString(credential.getAaguid()) : null,
                credential != null ? B64URL.encodeToString(credential.getCredentialId()) : null,
                device.getStatus().name(),
                DateTimeFormatter.ISO_INSTANT.format(device.getCreatedAt()),
                device.getLastUsedAt() != null ? DateTimeFormatter.ISO_INSTANT.format(device.getLastUsedAt()) : null);
    }

    private static String aaguidToUuidString(byte[] aaguid) {
        ByteBuffer bb = ByteBuffer.wrap(aaguid);
        long high = bb.getLong();
        long low = bb.getLong();
        return new UUID(high, low).toString();
    }
}
