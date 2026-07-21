package com.fido.server.bootstrap;

import com.fido.server.config.FidoProperties;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.TenantStatus;
import com.fido.server.repository.TenantRepository;
import com.fido.server.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 開發用種子資料：依 {@code fido.dev-seed.*} 設定建立一個示範租戶，方便本機以固定 API Key
 * 直接呼叫端點測試（無需先手動建租戶）。僅在 {@code fido.dev-seed.enabled=true} 時執行
 * （骨架預設開啟；devops-engineer 建置正式租戶管理流程後，應將此類別停用或移除）。
 */
@Component
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final FidoProperties properties;
    private final TenantRepository tenantRepository;
    private final ApiKeyService apiKeyService;

    public DevDataSeeder(FidoProperties properties, TenantRepository tenantRepository, ApiKeyService apiKeyService) {
        this.properties = properties;
        this.tenantRepository = tenantRepository;
        this.apiKeyService = apiKeyService;
    }

    @Override
    public void run(String... args) {
        FidoProperties.DevSeed seed = properties.getDevSeed();
        if (!seed.isEnabled()) {
            return;
        }
        Tenant tenant = new Tenant();
        tenant.setName(seed.getTenantName());
        tenant.setRpId(seed.getRpId());
        tenant.setExpectedOrigin("[\"" + seed.getExpectedOrigin() + "\"]");
        tenant.setApiKeyHash(apiKeyService.hash(seed.getApiKey()));
        tenant.setApiKeyPrefix(apiKeyService.prefix(seed.getApiKey()));
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setRateLimitTps(properties.getRateLimit().getDefaultTps());
        tenantRepository.save(tenant);

        log.info("[DEV-SEED] 已建立示範租戶 '{}'（rpId={}），本機測試可用 X-API-Key: {}",
                seed.getTenantName(), seed.getRpId(), seed.getApiKey());
    }
}
