package com.fido.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FIDO 驗證伺服器 — 骨架版本。
 *
 * <p>架構情境 A（標準 WebAuthn，同裝置），對應 docs/api-contract.md 與 docs/db-schema.md。
 * Persistence 層目前為 in-memory 實作（見 {@code com.fido.server.repository.inmemory}），
 * 待 devops-engineer 建置 SQL Server 後可替換為 JPA/JDBC 實作而不影響 service/controller 層。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FidoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FidoServerApplication.class, args);
    }
}
