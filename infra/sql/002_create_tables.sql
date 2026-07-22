/*
==============================================================================
 002_create_tables.sql
 建立七張核心表（DDL 依據 docs/db-schema.md 第 3-9 節「權威 schema」）

 建立順序依外鍵相依性排列，且此順序已可直接依序執行（後面的表只會參照前面已建立的表）：
   1. tenants
   2. fido_user_ref        -> tenants
   3. fido_credentials     -> fido_user_ref, tenants
   4. bound_devices        -> fido_credentials, fido_user_ref, tenants
   5. auth_challenges      -> tenants, fido_user_ref
   6. audit_log            -> tenants, fido_user_ref（device_pk 依 db-schema.md DB16 不設外鍵）
   7. tenant_app_bindings  -> tenants（db-schema.md 第 9 節 / DB17：原生 App 情境的 App 授權登錄）

 PK / UNIQUE / CHECK / FK 皆內嵌於各自 CREATE TABLE 陳述式中（而非拆到獨立檔案），
 原因：SQL Server 建表當下即可宣告參照已存在資料表的外鍵，內嵌可讀性較佳、且與
 docs/db-schema.md 逐表 DDL 對齊，方便日後比對維護。非唯一的次要索引另外拆到
 003_create_indexes.sql，該檔對應 docs/db-schema.md 每表 CREATE INDEX 陳述式。

 對照 docs/db-schema.md 過程中的訂正：
   本檔內容為逐字採用該文件章節 3-8 的 DDL；經比對第 9 節「索引與外鍵總表」，
   欄位、PK/UNIQUE/INDEX/FK 均一致，未發現需訂正之處（詳見部署完成後的回報說明）。

 執行方式：對 FidoServerDb 資料庫執行（需先執行 001_create_database.sql）。
 冪等性：每張表前加 IF OBJECT_ID(...) IS NULL 防呆，可重複執行而不報錯。
==============================================================================
*/

USE FidoServerDb;
GO
SET NOCOUNT ON;
GO

------------------------------------------------------------------------------
-- 3. tenants
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.tenants', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tenants (
        tenant_id       BIGINT IDENTITY(1,1) NOT NULL,
        tenant_uid      UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_tenants_uid DEFAULT NEWID(),
        name            NVARCHAR(200) NOT NULL,
        rp_id           NVARCHAR(255) NOT NULL,
        expected_origin NVARCHAR(512) NOT NULL,
        api_key_hash    VARBINARY(32) NOT NULL,
        api_key_prefix  NVARCHAR(12)  NOT NULL,
        status          NVARCHAR(20)  NOT NULL CONSTRAINT DF_tenants_status DEFAULT 'ACTIVE',
        rate_limit_tps  INT           NOT NULL CONSTRAINT DF_tenants_rate DEFAULT 100,
        created_at      DATETIME2(3)  NOT NULL CONSTRAINT DF_tenants_created DEFAULT SYSUTCDATETIME(),
        updated_at      DATETIME2(3)  NOT NULL CONSTRAINT DF_tenants_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_tenants PRIMARY KEY (tenant_id),
        CONSTRAINT UQ_tenants_uid UNIQUE (tenant_uid),
        CONSTRAINT UQ_tenants_rpid UNIQUE (rp_id),
        CONSTRAINT UQ_tenants_apikey UNIQUE (api_key_hash),
        CONSTRAINT CK_tenants_status CHECK (status IN ('ACTIVE','DISABLED'))
    );
END
GO

------------------------------------------------------------------------------
-- 4. fido_user_ref
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.fido_user_ref', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.fido_user_ref (
        user_ref_id      BIGINT IDENTITY(1,1) NOT NULL,
        tenant_id        BIGINT NOT NULL,
        external_user_id NVARCHAR(255) NOT NULL,
        user_handle      VARBINARY(64) NOT NULL,
        display_name     NVARCHAR(255) NULL,
        created_at       DATETIME2(3) NOT NULL CONSTRAINT DF_userref_created DEFAULT SYSUTCDATETIME(),
        updated_at       DATETIME2(3) NOT NULL CONSTRAINT DF_userref_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_fido_user_ref PRIMARY KEY (user_ref_id),
        CONSTRAINT FK_userref_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
        CONSTRAINT UQ_userref_extid UNIQUE (tenant_id, external_user_id),
        CONSTRAINT UQ_userref_handle UNIQUE (tenant_id, user_handle)
    );
END
GO

------------------------------------------------------------------------------
-- 5. fido_credentials
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.fido_credentials', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.fido_credentials (
        credential_pk        BIGINT IDENTITY(1,1) NOT NULL,
        user_ref_id          BIGINT NOT NULL,
        tenant_id            BIGINT NOT NULL,
        credential_id        VARBINARY(1024) NOT NULL,
        credential_id_sha256 VARBINARY(32) NOT NULL,
        public_key           VARBINARY(512) NOT NULL,
        cose_alg              INT NOT NULL,
        sign_count            BIGINT NOT NULL CONSTRAINT DF_cred_signcount DEFAULT 0,
        aaguid                BINARY(16) NULL,
        transports            NVARCHAR(100) NULL,
        attestation_format    NVARCHAR(50) NULL,
        status                NVARCHAR(20) NOT NULL CONSTRAINT DF_cred_status DEFAULT 'ACTIVE',
        revoked_at            DATETIME2(3) NULL,
        revoked_reason        NVARCHAR(50) NULL,
        created_at            DATETIME2(3) NOT NULL CONSTRAINT DF_cred_created DEFAULT SYSUTCDATETIME(),
        updated_at            DATETIME2(3) NOT NULL CONSTRAINT DF_cred_updated DEFAULT SYSUTCDATETIME(),
        last_used_at          DATETIME2(3) NULL,
        CONSTRAINT PK_fido_credentials PRIMARY KEY (credential_pk),
        CONSTRAINT FK_cred_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
        CONSTRAINT FK_cred_tenant  FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
        CONSTRAINT UQ_cred_idhash  UNIQUE (tenant_id, credential_id_sha256),
        CONSTRAINT CK_cred_status  CHECK (status IN ('ACTIVE','REVOKED')),
        CONSTRAINT CK_cred_revreason CHECK (revoked_reason IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED'))
    );
END
GO

------------------------------------------------------------------------------
-- 6. bound_devices
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.bound_devices', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.bound_devices (
        device_pk           BIGINT IDENTITY(1,1) NOT NULL,
        device_id           UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_dev_id DEFAULT NEWID(),
        credential_pk       BIGINT NOT NULL,
        user_ref_id         BIGINT NOT NULL,
        tenant_id           BIGINT NOT NULL,
        device_name         NVARCHAR(100) NULL,
        model                NVARCHAR(100) NULL,
        os_version           NVARCHAR(50) NULL,
        security_level       NVARCHAR(20) NOT NULL,
        attestation_summary  NVARCHAR(1000) NULL,
        status               NVARCHAR(20) NOT NULL CONSTRAINT DF_dev_status DEFAULT 'ACTIVE',
        revoked_at           DATETIME2(3) NULL,
        revoked_reason       NVARCHAR(50) NULL,
        created_at           DATETIME2(3) NOT NULL CONSTRAINT DF_dev_created DEFAULT SYSUTCDATETIME(),
        updated_at           DATETIME2(3) NOT NULL CONSTRAINT DF_dev_updated DEFAULT SYSUTCDATETIME(),
        last_used_at         DATETIME2(3) NULL,
        CONSTRAINT PK_bound_devices PRIMARY KEY (device_pk),
        CONSTRAINT UQ_dev_id UNIQUE (device_id),
        CONSTRAINT UQ_dev_cred UNIQUE (credential_pk),
        CONSTRAINT FK_dev_cred FOREIGN KEY (credential_pk) REFERENCES dbo.fido_credentials(credential_pk),
        CONSTRAINT FK_dev_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
        CONSTRAINT FK_dev_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
        CONSTRAINT CK_dev_seclevel CHECK (security_level IN ('TEE','STRONG_BOX')),
        CONSTRAINT CK_dev_status CHECK (status IN ('ACTIVE','REVOKED')),
        CONSTRAINT CK_dev_revreason CHECK (revoked_reason IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED'))
    );
END
GO

------------------------------------------------------------------------------
-- 7. auth_challenges
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.auth_challenges', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.auth_challenges (
        challenge_pk  BIGINT IDENTITY(1,1) NOT NULL,
        ceremony_id   NVARCHAR(64) NOT NULL,
        tenant_id     BIGINT NOT NULL,
        user_ref_id   BIGINT NULL,
        challenge     VARBINARY(64) NOT NULL,
        ceremony_type NVARCHAR(20) NOT NULL,
        status        NVARCHAR(20) NOT NULL CONSTRAINT DF_chal_status DEFAULT 'PENDING',
        expires_at    DATETIME2(3) NOT NULL,
        consumed_at   DATETIME2(3) NULL,
        created_at    DATETIME2(3) NOT NULL CONSTRAINT DF_chal_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_auth_challenges PRIMARY KEY (challenge_pk),
        CONSTRAINT UQ_chal_ceremony UNIQUE (ceremony_id),
        CONSTRAINT FK_chal_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
        CONSTRAINT FK_chal_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
        CONSTRAINT CK_chal_type CHECK (ceremony_type IN ('REGISTRATION','AUTHENTICATION')),
        CONSTRAINT CK_chal_status CHECK (status IN ('PENDING','CONSUMED','EXPIRED'))
    );
END
GO

------------------------------------------------------------------------------
-- 8. audit_log
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.audit_log', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.audit_log (
        audit_id     BIGINT IDENTITY(1,1) NOT NULL,
        tenant_id    BIGINT NULL,
        user_ref_id  BIGINT NULL,
        device_pk    BIGINT NULL,
        event_type   NVARCHAR(50) NOT NULL,
        outcome      NVARCHAR(20) NOT NULL,
        request_id   NVARCHAR(64) NULL,
        ip_address   NVARCHAR(45) NULL,
        detail       NVARCHAR(MAX) NULL,
        created_at   DATETIME2(3) NOT NULL CONSTRAINT DF_audit_created DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_audit_log PRIMARY KEY (audit_id),
        CONSTRAINT FK_audit_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
        CONSTRAINT FK_audit_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
        CONSTRAINT CK_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE'))
    );
    -- device_pk 刻意不建外鍵（docs/db-schema.md DB16）：避免裝置軟刪除/資料清理時
    -- 反而被稽核列的外鍵參照卡住；一致性由應用層保證。
END
GO

------------------------------------------------------------------------------
-- 9. tenant_app_bindings（原生 App 情境的 Digital Asset Links 授權登錄，db-schema.md DB17）
------------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.tenant_app_bindings', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tenant_app_bindings (
        app_binding_pk          BIGINT IDENTITY(1,1) NOT NULL,
        binding_uid             UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_appbind_uid DEFAULT NEWID(),
        tenant_id               BIGINT NOT NULL,
        package_name            NVARCHAR(255) NOT NULL,
        sha256_cert_fingerprint VARBINARY(32) NOT NULL,
        apk_key_hash_origin     NVARCHAR(120) NOT NULL,
        label                   NVARCHAR(100) NULL,
        status                  NVARCHAR(20) NOT NULL CONSTRAINT DF_appbind_status DEFAULT 'ACTIVE',
        revoked_at              DATETIME2(3) NULL,
        revoked_reason          NVARCHAR(50) NULL,
        created_at              DATETIME2(3) NOT NULL CONSTRAINT DF_appbind_created DEFAULT SYSUTCDATETIME(),
        updated_at              DATETIME2(3) NOT NULL CONSTRAINT DF_appbind_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_tenant_app_bindings PRIMARY KEY (app_binding_pk),
        CONSTRAINT UQ_appbind_uid UNIQUE (binding_uid),
        CONSTRAINT UQ_appbind_tenant_pkg_fp UNIQUE (tenant_id, package_name, sha256_cert_fingerprint),
        CONSTRAINT FK_appbind_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
        CONSTRAINT CK_appbind_status CHECK (status IN ('ACTIVE','REVOKED')),
        CONSTRAINT CK_appbind_revreason CHECK (revoked_reason IN ('ADMIN','KEY_ROTATION','SECURITY'))
    );
END
GO

PRINT N'002_create_tables.sql 執行完成：七張核心表已建立/確認存在。';
GO
