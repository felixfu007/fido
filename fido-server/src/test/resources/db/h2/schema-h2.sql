-- =============================================================================
-- schema-h2.sql — H2（MODE=MSSQLServer）本機開發/測試用 schema 初始化腳本
--
-- 這不是正式權威 DDL。權威來源是 docs/db-schema.md 第 3-8 節與
-- infra/sql/002_create_tables.sql + infra/sql/003_create_indexes.sql（devops-engineer 已在
-- 真正的 SQL Server 引擎（LocalDB）上驗證過可完整建置）。本檔只在 Spring profile "h2"
-- （見 application-h2.yml）啟用時，由 spring.sql.init 對 H2 in-memory 資料庫執行一次，讓
-- fido-server 的 JPA 層有真正的資料庫可以測（H2 對 SQL Server 有 MSSQL 相容模式，但兩者
-- 並非同一套引擎，不保證每個 T-SQL 語法/型別都能逐字通過 H2 剖析器，因此本檔與正式 DDL在
-- 型別／語法上有少數必要差異，逐一列在下方；除了這些差異外，欄位名稱/可為 null/預設值/
-- PK/UNIQUE/CHECK/FK 均與權威 DDL 一致）。
--
-- 與 infra/sql/002_create_tables.sql 的已知差異（且僅有這些差異）：
--   1. 不含 DEFAULT NEWID() / DEFAULT SYSUTCDATETIME() / DEFAULT 'ACTIVE' 等欄位預設值：
--      應用層（domain 物件建構子，如 Tenant.tenantUid = UUID.randomUUID()、
--      *.status = ACTIVE、*.createdAt = Instant.now()）在 INSERT 前一律已賦值，
--      JPA entity 對應欄位皆為 NOT NULL 且應用層保證寫入前已有值，故本檔省略 DB 端 DEFAULT
--      子句以降低與 H2 語法相容性風險；正式 SQL Server DDL 仍保留 DEFAULT（多一層防呆）。
--   2. `aaguid` 由權威 DDL 的固定長度 BINARY(16) 改為等長 VARBINARY(16)：
--      Hibernate 對 byte[] 欄位預設產生/驗證的型別類別是「可變長度二進位」，若照抄
--      BINARY(16)（固定長度）在 ddl-auto=validate 下可能被判為型別不符；由於本專案寫入時
--      一律是完整 16 bytes（不足或超過的情形本就是應用層 bug），VARBINARY(16) 與
--      BINARY(16) 在本專案實際使用情境下行為等價，只在「值不足 16 bytes 時是否補零到定長」
--      這種邊界語意上與真正 SQL Server 的 BINARY(16) 不同——這點只有接上真正 SQL Server
--      才能實際驗證，列為已知風險（見任務回報「H2 與真實 SQL Server 落差」）。
--   3. `audit_log.detail`（權威型別 NVARCHAR(MAX)）改用 CLOB：
--      H2 的 VARCHAR/NVARCHAR 型別在不指定長度時容量已經很大，但為了明確對應 JPA entity 上
--      的 @Lob 標註（AuditLogEntity.detail），此處改用 H2 原生 CLOB，兩者在「儲存任意長度
--      文字」這個功能面等價；SQL Server 端 NVARCHAR(MAX) 與 CLOB 的實際儲存/索引/效能特性
--      不同，未在本檔驗證。
--   4. 省略 infra/sql/003_create_indexes.sql 的非唯一次要索引（IX_*）：
--      這些索引只影響查詢效能，不影響資料正確性/約束語意，ddl-auto=validate 也不會檢查
--      它們是否存在；為了讓本檔維持精簡、專注在「JPA mapping 是否正確」這個目的，予以省略。
--      正式 SQL Server 部署仍以 infra/sql/003_create_indexes.sql 為準。
--
-- 除上述 4 點外，資料表/欄位/型別長度/NOT NULL/PK/UNIQUE/CHECK/FK 均逐字對齊
-- infra/sql/002_create_tables.sql（= docs/db-schema.md 第 3-8 節權威 DDL）。
--
-- 【重要】本檔逐字寫小寫的表名/欄位名（對齊 docs/db-schema.md），但實際連線字串
-- （application-h2.yml）刻意不加 DATABASE_TO_UPPER=false —— 也就是說 H2 仍會依其預設行為
-- 把這裡所有未加引號的識別字摺為大寫儲存。這不影響任何一行程式碼或 SQL 的正確性（Hibernate
-- 產生的所有 DML 也一律用它自己正規化後的大寫名稱，兩邊全程自動保持一致），純粹是 H2 內部
-- catalog 的儲存表現形式；原因與踩到的坑見 application-h2.yml 檔頭「H2 URL 參數說明」一節。
-- =============================================================================

CREATE TABLE tenants (
    tenant_id       BIGINT IDENTITY(1,1) NOT NULL,
    tenant_uid      UNIQUEIDENTIFIER NOT NULL,
    name            NVARCHAR(200) NOT NULL,
    rp_id           NVARCHAR(255) NOT NULL,
    expected_origin NVARCHAR(512) NOT NULL,
    api_key_hash    VARBINARY(32) NOT NULL,
    api_key_prefix  NVARCHAR(12)  NOT NULL,
    status          NVARCHAR(20)  NOT NULL,
    rate_limit_tps  INT           NOT NULL,
    created_at      DATETIME2(3)  NOT NULL,
    updated_at      DATETIME2(3)  NOT NULL,
    CONSTRAINT PK_tenants PRIMARY KEY (tenant_id),
    CONSTRAINT UQ_tenants_uid UNIQUE (tenant_uid),
    CONSTRAINT UQ_tenants_rpid UNIQUE (rp_id),
    CONSTRAINT UQ_tenants_apikey UNIQUE (api_key_hash),
    CONSTRAINT CK_tenants_status CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE TABLE fido_user_ref (
    user_ref_id      BIGINT IDENTITY(1,1) NOT NULL,
    tenant_id        BIGINT NOT NULL,
    external_user_id NVARCHAR(255) NOT NULL,
    user_handle      VARBINARY(64) NOT NULL,
    display_name     NVARCHAR(255) NULL,
    created_at       DATETIME2(3) NOT NULL,
    updated_at       DATETIME2(3) NOT NULL,
    CONSTRAINT PK_fido_user_ref PRIMARY KEY (user_ref_id),
    CONSTRAINT FK_userref_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id),
    CONSTRAINT UQ_userref_extid UNIQUE (tenant_id, external_user_id),
    CONSTRAINT UQ_userref_handle UNIQUE (tenant_id, user_handle)
);

CREATE TABLE fido_credentials (
    credential_pk        BIGINT IDENTITY(1,1) NOT NULL,
    user_ref_id          BIGINT NOT NULL,
    tenant_id            BIGINT NOT NULL,
    credential_id        VARBINARY(1024) NOT NULL,
    credential_id_sha256 VARBINARY(32) NOT NULL,
    public_key           VARBINARY(512) NOT NULL,
    cose_alg              INT NOT NULL,
    sign_count            BIGINT NOT NULL,
    aaguid                VARBINARY(16) NULL,
    transports            NVARCHAR(100) NULL,
    attestation_format    NVARCHAR(50) NULL,
    status                NVARCHAR(20) NOT NULL,
    revoked_at            DATETIME2(3) NULL,
    revoked_reason        NVARCHAR(50) NULL,
    created_at            DATETIME2(3) NOT NULL,
    updated_at            DATETIME2(3) NOT NULL,
    last_used_at          DATETIME2(3) NULL,
    CONSTRAINT PK_fido_credentials PRIMARY KEY (credential_pk),
    CONSTRAINT FK_cred_userref FOREIGN KEY (user_ref_id) REFERENCES fido_user_ref(user_ref_id),
    CONSTRAINT FK_cred_tenant  FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id),
    CONSTRAINT UQ_cred_idhash  UNIQUE (tenant_id, credential_id_sha256),
    CONSTRAINT CK_cred_status  CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT CK_cred_revreason CHECK (revoked_reason IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED'))
);

CREATE TABLE bound_devices (
    device_pk           BIGINT IDENTITY(1,1) NOT NULL,
    device_id           UNIQUEIDENTIFIER NOT NULL,
    credential_pk       BIGINT NOT NULL,
    user_ref_id         BIGINT NOT NULL,
    tenant_id           BIGINT NOT NULL,
    device_name         NVARCHAR(100) NULL,
    model                NVARCHAR(100) NULL,
    os_version           NVARCHAR(50) NULL,
    security_level       NVARCHAR(20) NOT NULL,
    attestation_summary  NVARCHAR(1000) NULL,
    status               NVARCHAR(20) NOT NULL,
    revoked_at           DATETIME2(3) NULL,
    revoked_reason       NVARCHAR(50) NULL,
    created_at           DATETIME2(3) NOT NULL,
    updated_at           DATETIME2(3) NOT NULL,
    last_used_at         DATETIME2(3) NULL,
    CONSTRAINT PK_bound_devices PRIMARY KEY (device_pk),
    CONSTRAINT UQ_dev_id UNIQUE (device_id),
    CONSTRAINT UQ_dev_cred UNIQUE (credential_pk),
    CONSTRAINT FK_dev_cred FOREIGN KEY (credential_pk) REFERENCES fido_credentials(credential_pk),
    CONSTRAINT FK_dev_userref FOREIGN KEY (user_ref_id) REFERENCES fido_user_ref(user_ref_id),
    CONSTRAINT FK_dev_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id),
    CONSTRAINT CK_dev_seclevel CHECK (security_level IN ('TEE','STRONG_BOX')),
    CONSTRAINT CK_dev_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT CK_dev_revreason CHECK (revoked_reason IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED'))
);

CREATE TABLE auth_challenges (
    challenge_pk  BIGINT IDENTITY(1,1) NOT NULL,
    ceremony_id   NVARCHAR(64) NOT NULL,
    tenant_id     BIGINT NOT NULL,
    user_ref_id   BIGINT NULL,
    challenge     VARBINARY(64) NOT NULL,
    ceremony_type NVARCHAR(20) NOT NULL,
    status        NVARCHAR(20) NOT NULL,
    expires_at    DATETIME2(3) NOT NULL,
    consumed_at   DATETIME2(3) NULL,
    created_at    DATETIME2(3) NOT NULL,
    CONSTRAINT PK_auth_challenges PRIMARY KEY (challenge_pk),
    CONSTRAINT UQ_chal_ceremony UNIQUE (ceremony_id),
    CONSTRAINT FK_chal_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id),
    CONSTRAINT FK_chal_userref FOREIGN KEY (user_ref_id) REFERENCES fido_user_ref(user_ref_id),
    CONSTRAINT CK_chal_type CHECK (ceremony_type IN ('REGISTRATION','AUTHENTICATION')),
    CONSTRAINT CK_chal_status CHECK (status IN ('PENDING','CONSUMED','EXPIRED'))
);

CREATE TABLE audit_log (
    audit_id     BIGINT IDENTITY(1,1) NOT NULL,
    tenant_id    BIGINT NULL,
    user_ref_id  BIGINT NULL,
    device_pk    BIGINT NULL,
    event_type   NVARCHAR(50) NOT NULL,
    outcome      NVARCHAR(20) NOT NULL,
    request_id   NVARCHAR(64) NULL,
    ip_address   NVARCHAR(45) NULL,
    detail       CLOB NULL,
    created_at   DATETIME2(3) NOT NULL,
    CONSTRAINT PK_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT FK_audit_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id),
    CONSTRAINT FK_audit_userref FOREIGN KEY (user_ref_id) REFERENCES fido_user_ref(user_ref_id),
    CONSTRAINT CK_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE'))
);
