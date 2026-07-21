package com.fido.server.context;

import com.fido.server.domain.Tenant;

/**
 * 目前請求已通過 API Key 認證的租戶，以 ThreadLocal 承載。
 * 由 {@code ApiKeyAuthFilter} 於請求開始時設定、結束時清除。
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static Tenant get() {
        return CURRENT.get();
    }

    public static Tenant require() {
        Tenant tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException("TenantContext 尚未設定；此端點是否遺漏 ApiKeyAuthFilter？");
        }
        return tenant;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
