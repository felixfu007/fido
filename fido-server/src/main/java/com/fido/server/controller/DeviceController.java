package com.fido.server.controller;

import com.fido.server.context.TenantContext;
import com.fido.server.dto.response.DeviceListResponse;
import com.fido.server.dto.response.DeviceRevokeResponse;
import com.fido.server.service.DeviceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 對應 api-contract.md 第 4 節：裝置管理 API。 */
@RestController
@RequestMapping("/api/v1/users/{externalUserId}/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public DeviceListResponse list(@PathVariable String externalUserId,
                                    @RequestParam(defaultValue = "ACTIVE") String status,
                                    @RequestParam(defaultValue = "50") int limit) {
        return deviceService.listDevices(TenantContext.require(), externalUserId, status, limit);
    }

    @DeleteMapping("/{deviceId}")
    public DeviceRevokeResponse revoke(@PathVariable String externalUserId, @PathVariable String deviceId) {
        return deviceService.revokeDevice(TenantContext.require(), externalUserId, deviceId);
    }
}
