package com.fido.server.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.domain.AuditLog;
import com.fido.server.domain.SigningKey;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.domain.enums.TenantStatus;
import com.fido.server.repository.AuditLogRepository;
import com.fido.server.repository.SigningKeyRepository;
import com.fido.server.repository.TenantAppBindingRepository;
import com.fido.server.repository.TenantRepository;
import com.fido.server.service.ApiKeyService;
import com.fido.server.service.ApkKeyHashOriginFormatter;
import com.fido.server.service.SigningKeyFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台維運方在伺服器主機本機執行的一次性管理指令，僅在 {@code admin-cli} Spring profile
 * active 時才是 bean（見 {@code application-admin-cli.yml}：{@code spring.main.web-application
 * -type=none}，不開任何網路端口）。三個子指令由 {@code --fido.admin.command=} 挑選，詳見
 * CLAUDE.md「租戶開通 / 簽章金鑰 CLI 決策」。
 *
 * <p>執行完畢一律呼叫 {@link System#exit(int)}：成功 0、已知錯誤（{@link AdminCliException}）
 * 非 0 且只印清楚的錯誤訊息（不印 stack trace）、真正未預期的例外印出堆疊供排查後同樣非 0
 * 結束——三種情況都不留行程掛著不退出。
 */
@Component
@Profile("admin-cli")
public class AdminCliRunner implements CommandLineRunner {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final AdminCliProperties properties;
    private final TenantRepository tenantRepository;
    private final TenantAppBindingRepository tenantAppBindingRepository;
    private final SigningKeyRepository signingKeyRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApiKeyService apiKeyService;
    private final SigningKeyFactory signingKeyFactory;
    private final ObjectMapper objectMapper;

    public AdminCliRunner(AdminCliProperties properties,
                           TenantRepository tenantRepository,
                           TenantAppBindingRepository tenantAppBindingRepository,
                           SigningKeyRepository signingKeyRepository,
                           AuditLogRepository auditLogRepository,
                           ApiKeyService apiKeyService,
                           SigningKeyFactory signingKeyFactory,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.tenantRepository = tenantRepository;
        this.tenantAppBindingRepository = tenantAppBindingRepository;
        this.signingKeyRepository = signingKeyRepository;
        this.auditLogRepository = auditLogRepository;
        this.apiKeyService = apiKeyService;
        this.signingKeyFactory = signingKeyFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        String command = properties.getCommand();
        int exitCode;
        try {
            if (command == null || command.isBlank()) {
                throw new AdminCliException(
                        "缺少 --fido.admin.command，需為 create-tenant / add-app-binding / rotate-signing-key 三者之一。");
            }
            exitCode = switch (command) {
                case "create-tenant" -> runCreateTenant();
                case "add-app-binding" -> runAddAppBinding();
                case "rotate-signing-key" -> runRotateSigningKey();
                default -> throw new AdminCliException(
                        "未知的 --fido.admin.command='" + command
                                + "'，需為 create-tenant / add-app-binding / rotate-signing-key 三者之一。");
            };
        } catch (AdminCliException knownError) {
            System.err.println("[admin-cli] 錯誤：" + knownError.getMessage());
            exitCode = 1;
        } catch (Exception unexpected) {
            System.err.println("[admin-cli] 發生未預期的錯誤，以下為完整堆疊追蹤供排查：");
            unexpected.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    // -------------------------------------------------------------------
    // create-tenant
    // -------------------------------------------------------------------

    int runCreateTenant() {
        AdminCliProperties.Tenant t = properties.getTenant();
        requireNonBlank(t.getName(), "--fido.admin.tenant.name");
        requireNonBlank(t.getRpId(), "--fido.admin.tenant.rp-id");
        requireNonBlank(t.getExpectedOrigin(), "--fido.admin.tenant.expected-origin");

        if (tenantRepository.findByRpId(t.getRpId()).isPresent()) {
            throw new AdminCliException(
                    "rp_id '" + t.getRpId() + "' 已存在（UQ_tenants_rpid 唯一約束），租戶開通失敗。"
                            + "每個 rp_id 只能對應一個租戶，若要修改既有租戶請走其他維運程序，不要重複執行 create-tenant。");
        }

        String rawApiKey = generateRawApiKey();

        Tenant tenant = new Tenant();
        tenant.setName(t.getName());
        tenant.setRpId(t.getRpId());
        tenant.setExpectedOrigin(buildExpectedOriginStorageValue(t.getExpectedOrigin()));
        tenant.setApiKeyHash(apiKeyService.hash(rawApiKey));
        tenant.setApiKeyPrefix(apiKeyService.prefix(rawApiKey));
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setRateLimitTps(t.getRateLimitTps());

        Tenant saved = tenantRepository.save(tenant);

        writeAuditLog(saved.getTenantId(), null, "TENANT_PROVISIONED", Map.of(
                "tenantUid", String.valueOf(saved.getTenantUid()),
                "rpId", saved.getRpId(),
                "apiKeyPrefix", saved.getApiKeyPrefix()));

        printCreateTenantResult(saved, rawApiKey);
        return 0;
    }

    private void printCreateTenantResult(Tenant tenant, String rawApiKey) {
        String divider = "=".repeat(80);
        System.out.println(divider);
        System.out.println("租戶開通成功");
        System.out.println(divider);
        System.out.println("tenant_uid       : " + tenant.getTenantUid());
        System.out.println("name             : " + tenant.getName());
        System.out.println("rp_id            : " + tenant.getRpId());
        System.out.println("expected_origin  : " + tenant.getExpectedOrigin());
        System.out.println("rate_limit_tps   : " + tenant.getRateLimitTps());
        System.out.println();
        System.out.println("API KEY（以下明碼只印一次，之後系統無法再查回）：");
        System.out.println("  " + rawApiKey);
        System.out.println();
        System.out.println("警語：");
        System.out.println("  - 請立即透過安全管道（例如加密通訊、密碼管理工具）轉交給採用廠商。");
        System.out.println("  - 系統只存這組金鑰的雜湊，之後無法再查回明碼，請勿自行留存明碼副本。");
        System.out.println("  - 絕對不要把這組金鑰貼到任何 log 檔、issue tracker、聊天工具或版本控制系統。");
        System.out.println(divider);
    }

    // -------------------------------------------------------------------
    // add-app-binding
    // -------------------------------------------------------------------

    int runAddAppBinding() {
        AdminCliProperties.Tenant t = properties.getTenant();
        AdminCliProperties.App a = properties.getApp();
        requireNonBlank(t.getRpId(), "--fido.admin.tenant.rp-id");
        requireNonBlank(a.getPackageName(), "--fido.admin.app.package-name");
        requireNonBlank(a.getSha256Fingerprint(), "--fido.admin.app.sha256-fingerprint");

        Tenant tenant = tenantRepository.findByRpId(t.getRpId())
                .orElseThrow(() -> new AdminCliException(
                        "找不到 rp_id='" + t.getRpId() + "' 的租戶。請先以 create-tenant 開通租戶，"
                                + "再執行 add-app-binding（OB6：原生 App 綁定是租戶開通後的獨立人工 onboarding 步驟）。"));

        byte[] fingerprint = parseFingerprint(a.getSha256Fingerprint());
        String apkKeyHashOrigin = ApkKeyHashOriginFormatter.format(fingerprint);

        TenantAppBinding binding = new TenantAppBinding();
        binding.setTenantId(tenant.getTenantId());
        binding.setPackageName(a.getPackageName());
        binding.setSha256CertFingerprint(fingerprint);
        binding.setApkKeyHashOrigin(apkKeyHashOrigin);
        binding.setLabel(a.getLabel());
        binding.setStatus(RecordStatus.ACTIVE);

        TenantAppBinding saved = tenantAppBindingRepository.save(binding);

        writeAuditLog(tenant.getTenantId(), null, "TENANT_APP_BINDING_ADDED", Map.of(
                "rpId", tenant.getRpId(),
                "packageName", saved.getPackageName(),
                "apkKeyHashOrigin", saved.getApkKeyHashOrigin()));

        String divider = "=".repeat(80);
        System.out.println(divider);
        System.out.println("App 授權新增成功");
        System.out.println(divider);
        System.out.println("rp_id               : " + tenant.getRpId());
        System.out.println("binding_uid         : " + saved.getBindingUid());
        System.out.println("package_name        : " + saved.getPackageName());
        System.out.println("apk_key_hash_origin : " + saved.getApkKeyHashOrigin());
        if (saved.getLabel() != null) {
            System.out.println("label               : " + saved.getLabel());
        }
        System.out.println(divider);
        return 0;
    }

    // -------------------------------------------------------------------
    // rotate-signing-key
    // -------------------------------------------------------------------

    int runRotateSigningKey() {
        SigningKey active = signingKeyRepository.findActive()
                .orElseThrow(() -> new AdminCliException(
                        "找不到目前 status=ACTIVE 的 signing key，無法輪替。"
                                + "正常情況下 fido-server 首次啟動時就會自動產生一把，"
                                + "請確認本 CLI 連線的資料庫與正在運作的 fido-server 實例是同一個。"));

        String oldKid = active.getKid();
        active.setStatus(SigningKeyStatus.RETIRED);
        active.setRetiredAt(Instant.now());
        signingKeyRepository.save(active);

        SigningKey newKey = signingKeyFactory.generate(null);
        SigningKey savedNewKey = signingKeyRepository.save(newKey);

        writeAuditLog(null, null, "SIGNING_KEY_ROTATED", Map.of(
                "oldKid", oldKid,
                "newKid", savedNewKey.getKid()));

        String divider = "=".repeat(80);
        System.out.println(divider);
        System.out.println("Signing key 輪替完成");
        System.out.println(divider);
        System.out.println("舊 kid（已轉為 RETIRED） : " + oldKid);
        System.out.println("新 kid（現為 ACTIVE）    : " + savedNewKey.getKid());
        System.out.println();
        System.out.println("提醒：因 session JWT 僅 120 秒效期，JWKS 端點會同時發布 ACTIVE+RETIRED 公鑰，");
        System.out.println("過渡期內舊 kid 簽出、尚未過期的 JWT 仍可正常驗簽，無需額外處理。");
        System.out.println(divider);
        return 0;
    }

    // -------------------------------------------------------------------
    // 共用工具方法
    // -------------------------------------------------------------------

    private void writeAuditLog(Long tenantId, Long userRefId, String eventType, Map<String, String> detailFields) {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(tenantId);
        auditLog.setUserRefId(userRefId);
        auditLog.setEventType(eventType);
        auditLog.setOutcome(AuditOutcome.SUCCESS);
        auditLog.setDetail(toJson(detailFields));
        auditLog.setCreatedAt(Instant.now());
        auditLogRepository.save(auditLog);
    }

    private String toJson(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            // 稽核細節序列化失敗不應阻斷已成功的主要操作，退化為最陽春的可讀字串。
            return String.valueOf(fields);
        }
    }

    private static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new AdminCliException("缺少必要參數 " + paramName + "。");
        }
    }

    private static String generateRawApiKey() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return "fsk_" + B64URL.encodeToString(randomBytes);
    }

    /**
     * 對齊 {@code tenants.expected_origin} 現行「單一字串或 JSON 陣列字串」慣例
     * （見 {@code OriginValidator}）。輸入允許：
     * <ul>
     *   <li>已經是 JSON 陣列字串（以 {@code [} 開頭）：原樣採用；</li>
     *   <li>單一 origin：原樣存成純字串；</li>
     *   <li>以逗號分隔多個 origin（對應命令列重複帶入
     *       {@code --fido.admin.tenant.expected-origin=} 時，Spring
     *       {@code SimpleCommandLinePropertySource} 會合併成以逗號分隔的單一字串值）：
     *       轉成 JSON 陣列字串。</li>
     * </ul>
     */
    String buildExpectedOriginStorageValue(String rawValue) {
        String trimmed = rawValue.trim();
        if (trimmed.startsWith("[")) {
            return trimmed;
        }
        List<String> origins = Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (origins.isEmpty()) {
            throw new AdminCliException("--fido.admin.tenant.expected-origin 內容無效：" + rawValue);
        }
        if (origins.size() == 1) {
            return origins.get(0);
        }
        try {
            return objectMapper.writeValueAsString(origins);
        } catch (Exception e) {
            throw new AdminCliException("無法序列化 expected_origin 清單：" + rawValue);
        }
    }

    /**
     * 接受十六進位（可含冒號分隔，如 {@code assetlinks.json} 慣用格式）或 base64 的
     * SHA-256 指紋字串，轉成原始位元組。
     */
    static byte[] parseFingerprint(String raw) {
        String trimmed = raw.trim();
        String hexCandidate = trimmed.replace(":", "").replace(" ", "");
        if (hexCandidate.length() == 64 && hexCandidate.chars()
                .allMatch(c -> Character.digit(c, 16) != -1)) {
            byte[] bytes = new byte[32];
            for (int i = 0; i < 32; i++) {
                int hi = Character.digit(hexCandidate.charAt(i * 2), 16);
                int lo = Character.digit(hexCandidate.charAt(i * 2 + 1), 16);
                bytes[i] = (byte) ((hi << 4) + lo);
            }
            return bytes;
        }
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw new AdminCliException(
                    "--fido.admin.app.sha256-fingerprint 格式無法辨識，需為 64 個字元的十六進位"
                            + "（可含冒號分隔）或 base64：" + raw);
        }
    }
}
