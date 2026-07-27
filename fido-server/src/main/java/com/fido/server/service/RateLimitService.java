package com.fido.server.service;

import com.fido.server.domain.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【真實實作 — 簡化版但語意正確】對應 api-contract.md D3：每租戶速率限制，超過回
 * 429 RATE_LIMITED + Retry-After。
 *
 * <p>採固定時間窗（每秒重置）計數器，單機記憶體實作。多實例水平擴展情境下
 * 各節點各自計數，總量會略高於設定值；正式多節點部署若需要精確全域限流，
 * 建議改用集中式（如 Redis）實作，屬 devops-engineer / 後續優化範圍，不影響本骨架的
 * service/controller 介面。
 */
@Service
public class RateLimitService {

    private record Window(long windowSecond, AtomicInteger count) {
    }

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public RateLimitService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public boolean tryAcquire(Tenant tenant) {
        long currentSecond = System.currentTimeMillis() / 1000L;
        Window window = windows.compute(tenant.getTenantId(), (id, existing) -> {
            if (existing == null || existing.windowSecond() != currentSecond) {
                return new Window(currentSecond, new AtomicInteger(0));
            }
            return existing;
        });
        int count = window.count().incrementAndGet();
        boolean allowed = count <= tenant.getRateLimitTps();
        if (!allowed) {
            // 唯一允許帶租戶維度 tag 的 counter（見 MetricNames 標籤硬規則說明）：per-tenant
            // 限流才有意義，租戶數量少、tenant_uid 非個資。
            meterRegistry.counter(MetricNames.RATE_LIMIT_REJECTIONS,
                    MetricNames.TAG_TENANT, String.valueOf(tenant.getTenantUid())).increment();
        }
        return allowed;
    }
}
