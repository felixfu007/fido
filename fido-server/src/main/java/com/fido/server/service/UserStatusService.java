package com.fido.server.service;

import com.fido.server.domain.FidoUserRef;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.dto.response.FidoStatusResponse;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.FidoUserRefRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 對應 api-contract.md 5.1：查詢使用者 FIDO 綁定狀態。
 * 【真實實作】D7 防列舉：使用者不存在時回 200 + enrolled=false，不回 404。
 */
@Service
public class UserStatusService {

    private final FidoUserRefRepository fidoUserRefRepository;
    private final FidoCredentialRepository fidoCredentialRepository;

    public UserStatusService(FidoUserRefRepository fidoUserRefRepository,
                              FidoCredentialRepository fidoCredentialRepository) {
        this.fidoUserRefRepository = fidoUserRefRepository;
        this.fidoCredentialRepository = fidoCredentialRepository;
    }

    public FidoStatusResponse getStatus(Tenant tenant, String externalUserId) {
        Optional<FidoUserRef> userRefOpt = fidoUserRefRepository
                .findByTenantIdAndExternalUserId(tenant.getTenantId(), externalUserId);
        if (userRefOpt.isEmpty()) {
            return new FidoStatusResponse(externalUserId, false, 0, false);
        }
        long activeCount = fidoCredentialRepository
                .countByUserRefIdAndStatus(userRefOpt.get().getUserRefId(), RecordStatus.ACTIVE);
        boolean enrolled = activeCount > 0;
        return new FidoStatusResponse(externalUserId, enrolled, (int) activeCount, enrolled);
    }
}
