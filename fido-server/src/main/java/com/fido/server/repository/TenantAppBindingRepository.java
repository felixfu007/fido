package com.fido.server.repository;

import com.fido.server.domain.TenantAppBinding;
import com.fido.server.domain.enums.RecordStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * `tenant_app_bindings` 表的 persistence 介面（db-schema.md 第 9 節 / DB17）。
 *
 * <p>主要用途：{@link com.fido.server.service.OriginValidator} 於驗證 origin 時，讀取某租戶
 * 目前 ACTIVE 的 App 授權列，併入該租戶的 origin 允許清單（見 docs/origin-binding.md 第 5.3 節）。
 * v1 無 REST 端點寫入本表（OB6：人工 onboarding），{@link #save} 目前僅供測試/未來管理工具使用。
 */
public interface TenantAppBindingRepository {

    List<TenantAppBinding> findByTenantIdAndStatus(Long tenantId, RecordStatus status);

    Optional<TenantAppBinding> findByBindingUid(UUID bindingUid);

    TenantAppBinding save(TenantAppBinding binding);
}
