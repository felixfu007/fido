package com.fido.server.repository;

import com.fido.server.domain.Tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * `tenants` 表的 persistence 介面。
 *
 * <p><b>目前實作</b>：{@code com.fido.server.repository.inmemory.InMemoryTenantRepository}
 * （記憶體 Map-based，程序重啟即遺失）。
 * <p><b>換成真實 DB 時</b>：devops-engineer 建置 SQL Server 後，dev-engineer 於此介面新增
 * Spring Data JPA / JDBC 實作類別並以 {@code @Primary} 或移除 in-memory 版本的方式替換，
 * 呼叫端（service 層）不需變動。
 */
public interface TenantRepository {

    Optional<Tenant> findById(Long tenantId);

    Optional<Tenant> findByTenantUid(UUID tenantUid);

    Optional<Tenant> findByApiKeyHash(byte[] apiKeyHash);

    Optional<Tenant> findByRpId(String rpId);

    /**
     * 供 admin CLI 唯讀 {@code list-tenants} 指令使用（CLAUDE.md「內部可觀測性 + 唯讀
     * list-tenants」交辦第 3 點）。回傳全部租戶，不分頁——租戶數量規模（平台簽約的採用廠商
     * 家數）遠低於會員/裝置規模，不需要分頁。
     */
    List<Tenant> findAll();

    Tenant save(Tenant tenant);
}
