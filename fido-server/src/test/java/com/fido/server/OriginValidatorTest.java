package com.fido.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.AppBindingRevokedReason;
import com.fido.server.domain.enums.OriginType;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.inmemory.InMemoryTenantAppBindingRepository;
import com.fido.server.service.OriginValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 對應 docs/origin-binding.md 第 5.3 節 / api-contract.md D12：origin 允許清單 =
 * {@code tenants.expected_origin}（web）∪ 該租戶 {@code tenant_app_bindings} 的 ACTIVE 列
 * （app）。本測試不透過 Spring context（避免依賴 fido.persistence.mode），直接以
 * {@link InMemoryTenantAppBindingRepository} 驗證 {@link OriginValidator} 的比對邏輯，
 * 對齊既有 {@code AttestationObjectBuilderTest} 之類「純邏輯、不需模擬器/資料庫」的測試模式。
 */
class OriginValidatorTest {

    private static final Long TENANT_ID = 1L;
    private static final String WEB_ORIGIN = "https://shop.example.com";
    private static final String APP_ORIGIN = "android:apk-key-hash:R2Zzb21lZmFrZWhhc2gtMzJieXRlcw";

    private InMemoryTenantAppBindingRepository appBindingRepository;
    private OriginValidator originValidator;

    @BeforeEach
    void setUp() {
        appBindingRepository = new InMemoryTenantAppBindingRepository();
        originValidator = new OriginValidator(new ObjectMapper(), appBindingRepository);
    }

    private Tenant tenantWithExpectedOrigin(String expectedOriginRaw) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT_ID);
        tenant.setName("Test Shop");
        tenant.setRpId("shop.example.com");
        tenant.setExpectedOrigin(expectedOriginRaw);
        return tenant;
    }

    private TenantAppBinding activeAppBinding(String apkKeyHashOrigin) {
        TenantAppBinding binding = new TenantAppBinding();
        binding.setTenantId(TENANT_ID);
        binding.setPackageName("com.shop.example");
        binding.setSha256CertFingerprint(new byte[32]);
        binding.setApkKeyHashOrigin(apkKeyHashOrigin);
        binding.setStatus(RecordStatus.ACTIVE);
        return binding;
    }

    @Test
    void webOriginMatchingSingleStringExpectedOriginIsAllowed() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);

        OriginValidator.OriginCheckResult result = originValidator.check(WEB_ORIGIN, tenant);

        assertThat(result.allowed()).isTrue();
        assertThat(result.originType()).isEqualTo(OriginType.WEB);
    }

    @Test
    void webOriginMatchingJsonArrayExpectedOriginIsAllowed() {
        Tenant tenant = tenantWithExpectedOrigin("[\"https://other.example.com\",\"" + WEB_ORIGIN + "\"]");

        OriginValidator.OriginCheckResult result = originValidator.check(WEB_ORIGIN, tenant);

        assertThat(result.allowed()).isTrue();
        assertThat(result.originType()).isEqualTo(OriginType.WEB);
    }

    @Test
    void appOriginMatchingActiveTenantAppBindingIsAllowed() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);
        appBindingRepository.save(activeAppBinding(APP_ORIGIN));

        OriginValidator.OriginCheckResult result = originValidator.check(APP_ORIGIN, tenant);

        assertThat(result.allowed()).isTrue();
        assertThat(result.originType()).isEqualTo(OriginType.NATIVE_APP);
    }

    @Test
    void originNotMatchingWebOrAnyActiveAppBindingIsNotAllowed() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);
        appBindingRepository.save(activeAppBinding(APP_ORIGIN));

        OriginValidator.OriginCheckResult result = originValidator.check("https://phishing.example.com", tenant);

        assertThat(result.allowed()).isFalse();
        assertThat(result.originType()).isNull();
    }

    @Test
    void revokedAppBindingIsNotTreatedAsAllowed() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);
        TenantAppBinding revoked = activeAppBinding(APP_ORIGIN);
        revoked.setStatus(RecordStatus.REVOKED);
        revoked.setRevokedAt(Instant.now());
        revoked.setRevokedReason(AppBindingRevokedReason.KEY_ROTATION);
        appBindingRepository.save(revoked);

        OriginValidator.OriginCheckResult result = originValidator.check(APP_ORIGIN, tenant);

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void appBindingFromAnotherTenantIsNotAllowed() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);
        TenantAppBinding otherTenantBinding = activeAppBinding(APP_ORIGIN);
        otherTenantBinding.setTenantId(TENANT_ID + 1);
        appBindingRepository.save(otherTenantBinding);

        OriginValidator.OriginCheckResult result = originValidator.check(APP_ORIGIN, tenant);

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void nullOriginOrNullTenantIsNotAllowed() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);

        assertThat(originValidator.check(null, tenant).allowed()).isFalse();
        assertThat(originValidator.check(WEB_ORIGIN, null).allowed()).isFalse();
    }

    @Test
    void isAllowedConvenienceMethodDelegatesToCheck() {
        Tenant tenant = tenantWithExpectedOrigin(WEB_ORIGIN);

        assertThat(originValidator.isAllowed(WEB_ORIGIN, tenant)).isTrue();
        assertThat(originValidator.isAllowed("https://phishing.example.com", tenant)).isFalse();
    }
}
