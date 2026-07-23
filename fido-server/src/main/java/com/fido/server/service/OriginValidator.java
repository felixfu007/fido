package com.fido.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.OriginType;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.TenantAppBindingRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 【真實實作】比對 clientDataJSON.origin 是否在租戶允許清單內。
 *
 * <p>允許清單 = {@code tenants.expected_origin}（web origin；db-schema.md，可存單一字串或 JSON
 * 陣列字串）∪ 該租戶 {@code tenant_app_bindings} 中 {@code status=ACTIVE} 列的
 * {@code apk_key_hash_origin}（app origin，僅原生 App opt-in 租戶有資料）。對應
 * docs/origin-binding.md 第 5.3 節 / api-contract.md D12：不符回 {@code 403 ORIGIN_NOT_ALLOWED}
 * （與 RP ID 不符的 {@code 403 RP_ID_MISMATCH} 區分，見 {@link com.fido.server.exception.ErrorCode}
 * Javadoc）。
 *
 * <p>比對命中的來源型別（{@link OriginType}）由呼叫端（{@code RegistrationService} /
 * {@code AuthenticationService}）用於 {@code audit_log.detail.originType}
 * （docs/origin-binding.md OB5 / api-contract.md D13）。
 */
@Component
public class OriginValidator {

    private final ObjectMapper objectMapper;
    private final TenantAppBindingRepository tenantAppBindingRepository;

    public OriginValidator(ObjectMapper objectMapper, TenantAppBindingRepository tenantAppBindingRepository) {
        this.objectMapper = objectMapper;
        this.tenantAppBindingRepository = tenantAppBindingRepository;
    }

    /**
     * 比對 origin，回傳是否允許以及（若允許）其來源型別。
     */
    public OriginCheckResult check(String origin, Tenant tenant) {
        if (origin == null || tenant == null) {
            return OriginCheckResult.notAllowed();
        }
        if (matchesExpectedOrigin(origin, tenant.getExpectedOrigin())) {
            return OriginCheckResult.allowed(OriginType.WEB);
        }
        List<TenantAppBinding> activeAppBindings =
                tenantAppBindingRepository.findByTenantIdAndStatus(tenant.getTenantId(), RecordStatus.ACTIVE);
        boolean appOriginMatches = activeAppBindings.stream()
                .anyMatch(binding -> origin.equals(binding.getApkKeyHashOrigin()));
        if (appOriginMatches) {
            return OriginCheckResult.allowed(OriginType.NATIVE_APP);
        }
        return OriginCheckResult.notAllowed();
    }

    /** 便利方法：僅需布林結果時使用（內部委派 {@link #check}）。 */
    public boolean isAllowed(String origin, Tenant tenant) {
        return check(origin, tenant).allowed();
    }

    @SuppressWarnings("unchecked")
    private boolean matchesExpectedOrigin(String origin, String expectedOriginRaw) {
        if (expectedOriginRaw == null) {
            return false;
        }
        try {
            List<Object> list = objectMapper.readValue(expectedOriginRaw, List.class);
            return list.stream().anyMatch(o -> origin.equals(String.valueOf(o)));
        } catch (Exception e) {
            return origin.equals(expectedOriginRaw);
        }
    }

    /** origin 比對結果：是否允許 + 若允許，其來源型別（{@code WEB} / {@code NATIVE_APP}）。 */
    public record OriginCheckResult(boolean allowed, OriginType originType) {

        static OriginCheckResult allowed(OriginType type) {
            return new OriginCheckResult(true, type);
        }

        static OriginCheckResult notAllowed() {
            return new OriginCheckResult(false, null);
        }
    }
}
