package com.fido.server.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.domain.AuditLog;
import com.fido.server.domain.SigningKey;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.domain.enums.TenantStatus;
import com.fido.server.repository.AuditLogRepository;
import com.fido.server.repository.SigningKeyRepository;
import com.fido.server.repository.TenantAppBindingRepository;
import com.fido.server.repository.TenantRepository;
import com.fido.server.repository.inmemory.InMemoryAuditLogRepository;
import com.fido.server.repository.inmemory.InMemorySigningKeyRepository;
import com.fido.server.repository.inmemory.InMemoryTenantAppBindingRepository;
import com.fido.server.repository.inmemory.InMemoryTenantRepository;
import com.fido.server.service.ApiKeyService;
import com.fido.server.service.SigningKeyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 測試三個 admin CLI 指令的核心邏輯（{@link AdminCliRunner#runCreateTenant()} 等，
 * package-private 供測試直接呼叫），不透過 {@link AdminCliRunner#run(String...)}——後者結尾
 * 一律呼叫 {@link System#exit(int)}，若在單元測試行程內呼叫會直接把整個測試 JVM 關掉。
 * {@code run()} 對 {@code System.exit} 的整合行為改以「實際執行一次 CLI」的手動驗證確認
 * （見任務回報），不在自動化測試涵蓋範圍內。
 *
 * <p>刻意使用 in-memory repository（而非 mock）：這三個指令的邏輯大量依賴「先寫入、再用另一個
 * 查詢方法讀回來檢查」（如 create-tenant 檢查 rp_id 是否已存在、add-app-binding 用 rp_id 找
 * 租戶），用真正會互動的 in-memory 實作比逐一 mock 更貼近真實行為、也更不容易漏測互動細節。
 */
class AdminCliRunnerTest {

    private TenantRepository tenantRepository;
    private TenantAppBindingRepository tenantAppBindingRepository;
    private SigningKeyRepository signingKeyRepository;
    private AuditLogRepository auditLogRepository;
    private AdminCliProperties properties;
    private AdminCliRunner runner;

    private ByteArrayOutputStream stdoutCapture;
    private PrintStream originalStdout;

    @BeforeEach
    void setUp() {
        tenantRepository = new InMemoryTenantRepository();
        tenantAppBindingRepository = new InMemoryTenantAppBindingRepository();
        signingKeyRepository = new InMemorySigningKeyRepository();
        auditLogRepository = new InMemoryAuditLogRepository();
        properties = new AdminCliProperties();

        runner = new AdminCliRunner(properties, tenantRepository, tenantAppBindingRepository,
                signingKeyRepository, auditLogRepository, new ApiKeyService(), new SigningKeyFactory(),
                new ObjectMapper());

        originalStdout = System.out;
        stdoutCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalStdout);
    }

    // -------------------------------------------------------------------
    // create-tenant
    // -------------------------------------------------------------------

    @Test
    void createTenant_happyPath_persistsTenantPrintsApiKeyOnceAndWritesAuditLog() throws Exception {
        properties.getTenant().setName("Demo Shop");
        properties.getTenant().setRpId("shop.example.com");
        properties.getTenant().setExpectedOrigin("https://shop.example.com");
        properties.getTenant().setRateLimitTps(50);

        int exitCode = runner.runCreateTenant();
        assertThat(exitCode).isEqualTo(0);

        Optional<Tenant> saved = tenantRepository.findByRpId("shop.example.com");
        assertThat(saved).isPresent();
        assertThat(saved.get().getName()).isEqualTo("Demo Shop");
        assertThat(saved.get().getExpectedOrigin()).isEqualTo("https://shop.example.com");
        assertThat(saved.get().getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(saved.get().getRateLimitTps()).isEqualTo(50);
        assertThat(saved.get().getApiKeyPrefix()).startsWith("fsk_");

        // 從擷取到的 stdout 解析出印出來的明碼 API Key，驗證它的雜湊真的等於落庫的 apiKeyHash
        // （證明「印出來的那把」就是「真的存進去、之後驗證會用到的那把」，不是兩份不一致的資料）。
        String output = stdoutCapture.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("API KEY").contains("只印一次").contains("勿").contains("log");
        String rawApiKey = extractIndentedLineAfter(output, "API KEY");
        assertThat(rawApiKey).startsWith("fsk_");
        assertThat(MessageDigest.getInstance("SHA-256").digest(rawApiKey.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(saved.get().getApiKeyHash());

        List<AuditLog> auditEvents = auditLogRepository.findByTenantIdAndUserRefId(
                saved.get().getTenantId(), null, 10);
        assertThat(auditEvents).anySatisfy(e -> assertThat(e.getEventType()).isEqualTo("TENANT_PROVISIONED"));
        // 稽核紀錄不可含明碼 API Key。
        assertThat(auditEvents).allSatisfy(e -> assertThat(e.getDetail()).doesNotContain(rawApiKey));
    }

    @Test
    void createTenant_expectedOriginWithMultipleCommaSeparatedValues_storesAsJsonArray() {
        properties.getTenant().setName("Multi Origin Shop");
        properties.getTenant().setRpId("multi.example.com");
        properties.getTenant().setExpectedOrigin("https://multi.example.com, https://multi-app.example.com");

        int exitCode = runner.runCreateTenant();
        assertThat(exitCode).isEqualTo(0);

        Optional<Tenant> saved = tenantRepository.findByRpId("multi.example.com");
        assertThat(saved).isPresent();
        assertThat(saved.get().getExpectedOrigin())
                .isEqualTo("[\"https://multi.example.com\",\"https://multi-app.example.com\"]");
    }

    @Test
    void createTenant_duplicateRpId_throwsAdminCliExceptionAndDoesNotCreateSecondTenant() {
        Tenant existing = new Tenant();
        existing.setName("Existing Shop");
        existing.setRpId("dup.example.com");
        existing.setExpectedOrigin("https://dup.example.com");
        existing.setApiKeyHash(new byte[32]);
        existing.setApiKeyPrefix("fsk_existing");
        tenantRepository.save(existing);

        properties.getTenant().setName("Second Shop");
        properties.getTenant().setRpId("dup.example.com");
        properties.getTenant().setExpectedOrigin("https://dup.example.com");

        assertThatThrownBy(() -> runner.runCreateTenant())
                .isInstanceOf(AdminCliException.class)
                .hasMessageContaining("dup.example.com")
                .hasMessageContaining("已存在");
    }

    @Test
    void createTenant_missingRequiredParam_throwsAdminCliException() {
        properties.getTenant().setRpId("no-name.example.com");
        properties.getTenant().setExpectedOrigin("https://no-name.example.com");
        // name 未設定。

        assertThatThrownBy(() -> runner.runCreateTenant())
                .isInstanceOf(AdminCliException.class)
                .hasMessageContaining("--fido.admin.tenant.name");
    }

    // -------------------------------------------------------------------
    // add-app-binding
    // -------------------------------------------------------------------

    @Test
    void addAppBinding_happyPath_persistsBindingWithFormattedOriginAndWritesAuditLog() {
        Tenant tenant = seedTenant("app.example.com");

        properties.getTenant().setRpId("app.example.com");
        properties.getApp().setPackageName("com.shop.example");
        properties.getApp().setSha256Fingerprint(
                "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83");
        properties.getApp().setLabel("正式版 App");

        int exitCode = runner.runAddAppBinding();
        assertThat(exitCode).isEqualTo(0);

        List<TenantAppBinding> bindings = tenantAppBindingRepository.findByTenantIdAndStatus(
                tenant.getTenantId(), com.fido.server.domain.enums.RecordStatus.ACTIVE);
        assertThat(bindings).hasSize(1);
        TenantAppBinding saved = bindings.get(0);
        assertThat(saved.getPackageName()).isEqualTo("com.shop.example");
        assertThat(saved.getLabel()).isEqualTo("正式版 App");
        assertThat(saved.getApkKeyHashOrigin()).startsWith("android:apk-key-hash:");

        List<AuditLog> auditEvents = auditLogRepository.findByTenantIdAndUserRefId(tenant.getTenantId(), null, 10);
        assertThat(auditEvents).anySatisfy(e -> assertThat(e.getEventType()).isEqualTo("TENANT_APP_BINDING_ADDED"));
    }

    @Test
    void addAppBinding_tenantNotFound_throwsAdminCliException() {
        properties.getTenant().setRpId("does-not-exist.example.com");
        properties.getApp().setPackageName("com.shop.example");
        properties.getApp().setSha256Fingerprint(
                "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83");

        assertThatThrownBy(() -> runner.runAddAppBinding())
                .isInstanceOf(AdminCliException.class)
                .hasMessageContaining("does-not-exist.example.com")
                .hasMessageContaining("找不到");
    }

    @Test
    void addAppBinding_unparseableFingerprint_throwsAdminCliException() {
        seedTenant("bad-fp.example.com");
        properties.getTenant().setRpId("bad-fp.example.com");
        properties.getApp().setPackageName("com.shop.example");
        properties.getApp().setSha256Fingerprint("not-a-valid-fingerprint!!");

        assertThatThrownBy(() -> runner.runAddAppBinding())
                .isInstanceOf(AdminCliException.class);
    }

    // -------------------------------------------------------------------
    // rotate-signing-key
    // -------------------------------------------------------------------

    @Test
    void rotateSigningKey_happyPath_retiresOldKeyAndCreatesNewActiveKey() {
        SigningKey initial = new SigningKeyFactory().generate("initial-kid");
        signingKeyRepository.save(initial);

        int exitCode = runner.runRotateSigningKey();
        assertThat(exitCode).isEqualTo(0);

        Optional<SigningKey> active = signingKeyRepository.findActive();
        assertThat(active).isPresent();
        assertThat(active.get().getKid()).isNotEqualTo("initial-kid");

        List<SigningKey> all = signingKeyRepository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(k -> {
            assertThat(k.getKid()).isEqualTo("initial-kid");
            assertThat(k.getStatus()).isEqualTo(SigningKeyStatus.RETIRED);
            assertThat(k.getRetiredAt()).isNotNull();
        });

        List<AuditLog> auditEvents = auditLogRepository.findByTenantIdAndUserRefId(null, null, 10);
        assertThat(auditEvents).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("SIGNING_KEY_ROTATED");
            assertThat(e.getDetail()).contains("initial-kid");
        });
    }

    @Test
    void rotateSigningKey_noActiveKeyExists_throwsAdminCliException() {
        assertThatThrownBy(() -> runner.runRotateSigningKey())
                .isInstanceOf(AdminCliException.class)
                .hasMessageContaining("ACTIVE");
    }

    // -------------------------------------------------------------------
    // list-tenants
    // -------------------------------------------------------------------

    @Test
    void listTenants_withExistingTenants_printsAllowedFieldsOnlyAndNeverWritesAuditLog() {
        Tenant tenantA = seedTenant("list-a.example.com");
        Tenant tenantB = seedTenant("list-b.example.com");

        int exitCode = runner.runListTenants();
        assertThat(exitCode).isEqualTo(0);

        String output = stdoutCapture.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("list-a.example.com").contains("list-b.example.com");
        assertThat(output).contains(tenantA.getTenantUid().toString());
        assertThat(output).contains(tenantB.getTenantUid().toString());
        assertThat(output).contains("expected_origin");

        // 嚴禁印出雜湊或前綴——即使 seedTenant() 沒有設定真正的雜湊值，這裡確認的是「輸出格式
        // 本身沒有 api_key_hash / api_key_prefix 這兩個欄位標籤」，而非只檢查某個具體數值缺席。
        assertThat(output).doesNotContain("api_key_hash").doesNotContain("api_key_prefix");

        // 唯讀指令：不應寫入任何 audit_log。
        assertThat(auditLogRepository.findByTenantIdAndUserRefId(tenantA.getTenantId(), null, 10)).isEmpty();
        assertThat(auditLogRepository.findByTenantIdAndUserRefId(tenantB.getTenantId(), null, 10)).isEmpty();
    }

    @Test
    void listTenants_noTenantsExist_printsEmptyResultWithoutError() {
        int exitCode = runner.runListTenants();
        assertThat(exitCode).isEqualTo(0);

        String output = stdoutCapture.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("共 0 筆");
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private Tenant seedTenant(String rpId) {
        Tenant tenant = new Tenant();
        tenant.setName("Seed Shop");
        tenant.setRpId(rpId);
        tenant.setExpectedOrigin("https://" + rpId);
        tenant.setApiKeyHash(new byte[32]);
        tenant.setApiKeyPrefix("fsk_seed0001");
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenantRepository.save(tenant);
    }

    /** 找出輸出裡標題行之後、下一個非空白行（即該區塊縮排呈現的內容值）。 */
    private static String extractIndentedLineAfter(String output, String headingContains) {
        String[] lines = output.split("\\R");
        boolean foundHeading = false;
        for (String line : lines) {
            if (foundHeading && !line.isBlank()) {
                return line.trim();
            }
            if (line.contains(headingContains)) {
                foundHeading = true;
            }
        }
        throw new IllegalStateException("在輸出裡找不到 '" + headingContains + "' 之後的內容行");
    }
}
