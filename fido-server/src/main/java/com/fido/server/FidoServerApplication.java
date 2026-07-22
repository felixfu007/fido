package com.fido.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FIDO 驗證伺服器 — 骨架版本。
 *
 * <p>架構情境 A（標準 WebAuthn，同裝置），對應 docs/api-contract.md 與 docs/db-schema.md。
 *
 * <p>Persistence 層依 {@code fido.persistence.mode} 切換 in-memory（{@code memory}，見
 * {@code com.fido.server.repository.inmemory}）或 JPA（{@code jpa}，預設，見
 * {@code com.fido.server.repository.jpa}）實作，service/controller 層不受影響。DataSource /
 * Hibernate JPA / Spring Data JPA 這四個自動組態在此處先整體排除，只由
 * {@link com.fido.server.config.JpaInfrastructureConfig} 依 {@code fido.persistence.mode}
 * 條件式匯入 —— 這樣 {@code mode=memory} 時完全不會嘗試建立任何資料庫連線（即使
 * classpath 上有 mssql-jdbc / h2）。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class FidoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FidoServerApplication.class, args);
    }
}
