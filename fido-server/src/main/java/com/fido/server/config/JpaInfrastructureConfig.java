package com.fido.server.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 持久層基礎設施總開關。
 *
 * <p>{@code com.fido.server.FidoServerApplication} 上已將 {@link DataSourceAutoConfiguration}、
 * {@link DataSourceTransactionManagerAutoConfiguration}、{@link HibernateJpaAutoConfiguration}、
 * {@link JpaRepositoriesAutoConfiguration} 這四個 Spring Boot 自動組態整體排除；本類別是它們
 * 唯一的重新啟用入口，且只在 {@code fido.persistence.mode=jpa}（預設，對齊 CLAUDE.md
 * 「獨立 SQL Server 實例」架構決策）時才會匯入。
 *
 * <p>{@code fido.persistence.mode=memory} 時本類別整個不生效 —— 不會建立 DataSource、不會
 * 嘗試連線任何資料庫（即使 classpath 上同時有 mssql-jdbc 與 h2 也不受影響），
 * {@code com.fido.server.repository.inmemory.*} 才是實際生效的 repository 實作。
 *
 * <p>「要不要走 JPA」（本類別 + {@code fido.persistence.mode}）與「JPA 要接哪個實際資料庫」
 * （{@code spring.datasource.*} 連線設定）是兩層不同的關注點：本類別生效後，實際連去 H2
 * 還是 SQL Server，完全取決於當時生效的 {@code spring.datasource.url}／驅動設定（見
 * {@code application.yml} 預設值為 SQL Server 連線語法，{@code application-h2.yml}（僅
 * test classpath）才會覆寫為 H2）。
 *
 * <p>【實作細節 1】{@link JpaRepositoriesAutoConfiguration} 本身沒有被 {@code @Import} 使用，而是
 * 改用 {@link EnableJpaRepositories} + {@link EntityScan} 明確指定套件：
 * {@code JpaRepositoriesAutoConfiguration} 內部依賴
 * {@code AutoConfigurationPackages}（由 {@code @EnableAutoConfiguration} 的 deferred import
 * 機制註冊），但本類別是透過 component scan 被一般 {@code @Configuration} 解析流程處理、早於
 * deferred import 階段執行，直接 {@code @Import(JpaRepositoriesAutoConfiguration.class)} 會在
 * repository 掃描當下找不到 {@code AutoConfigurationPackages} bean 而丟出
 * {@code IllegalStateException: Unable to retrieve @EnableAutoConfiguration base packages}；
 * 改用明確指定 base package 的 {@code @EnableJpaRepositories}/{@code @EntityScan} 可完全避開
 * 這個時序依賴。
 *
 * <p>【實作細節 2 — 踩過的坑】{@link DataSourceTransactionManagerAutoConfiguration} 刻意【不】被
 * {@code @Import}：一開始曾經把它跟 {@link HibernateJpaAutoConfiguration} 一起匯入，結果造成
 * repository 的 {@code @Transactional} 方法雖然「有」交易在跑（
 * {@code TransactionSynchronizationManager.isActualTransactionActive()} 回傳 true），
 * 但寫入完全沒有真的落地（{@code save()} 回傳的 entity ID 永遠是 null，資料庫裡也查不到剛寫入
 * 的列，explicit flush 甚至會丟出 {@code TransactionRequiredException: no transaction is in
 * progress}）。原因：{@code @Import} 依陣列順序處理，若
 * {@code DataSourceTransactionManagerAutoConfiguration} 排在
 * {@code HibernateJpaAutoConfiguration} 前面，它會先搶著建立一個「純 JDBC 用」的
 * {@code DataSourceTransactionManager} bean；等到 {@code HibernateJpaAutoConfiguration}
 * 處理時，它自己要建立的 {@code JpaTransactionManager}（唯一真正知道怎麼把 Hibernate
 * {@code EntityManager} 綁進交易的實作）因為 {@code @ConditionalOnMissingBean} 偵測到「已經有
 * 一個 TransactionManager 了」而放棄建立自己那份 —— 於是 {@code @Transactional} 代理程式碼實際
 * 綁的是那個對 JPA 一無所知的 {@code DataSourceTransactionManager}，Hibernate 端則完全遊離在
 * 這個交易之外。正常 Spring Boot 應用能避開這個問題，是因為它透過 deferred
 * auto-configuration 機制、以更精細的 {@code @ConditionalOnMissingBean} 排序規則決定兩者互斥，
 * 而不是單純依 {@code @Import} 陣列順序；本類別繞過了那套機制，所以必須手動確保「只匯入
 * JPA 專用的 transaction manager 來源」。單純不匯入
 * {@code DataSourceTransactionManagerAutoConfiguration} 即可讓
 * {@code HibernateJpaAutoConfiguration}／{@code JpaBaseConfiguration} 順利建立正確的
 * {@code JpaTransactionManager}（本專案不需要純 JDBC 的 {@code JdbcTemplate}，故不匯入它完全
 * 不影響任何功能）。
 */
@Configuration
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
@Import({
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@EnableJpaRepositories(basePackages = "com.fido.server.repository.jpa.springdata")
@EntityScan(basePackages = "com.fido.server.repository.jpa.entity")
public class JpaInfrastructureConfig {
}
