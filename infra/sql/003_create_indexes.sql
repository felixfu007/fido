/*
==============================================================================
 003_create_indexes.sql
 建立次要（非唯一）索引，對應 docs/db-schema.md 各表 CREATE INDEX 陳述式
 與第 9 節「索引與外鍵總表」的「其他索引」欄位。

 註：PK / UNIQUE 索引已隨 002_create_tables.sql 的表格約束一併建立，此檔僅補
 查詢效能用的非唯一索引。

 執行方式：對 FidoServerDb 資料庫執行（需先完成 002_create_tables.sql）。
 冪等性：每個索引前以 sys.indexes 檢查是否已存在，可重複執行。
==============================================================================
*/

USE FidoServerDb;
GO
SET NOCOUNT ON;
GO

-- tenants
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_tenants_apikey_prefix' AND object_id = OBJECT_ID(N'dbo.tenants'))
    CREATE INDEX IX_tenants_apikey_prefix ON dbo.tenants (api_key_prefix);
GO

-- fido_credentials
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_cred_userref_status' AND object_id = OBJECT_ID(N'dbo.fido_credentials'))
    CREATE INDEX IX_cred_userref_status ON dbo.fido_credentials (user_ref_id, status);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_cred_tenant_status' AND object_id = OBJECT_ID(N'dbo.fido_credentials'))
    CREATE INDEX IX_cred_tenant_status ON dbo.fido_credentials (tenant_id, status);
GO

-- bound_devices
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_dev_userref_status' AND object_id = OBJECT_ID(N'dbo.bound_devices'))
    CREATE INDEX IX_dev_userref_status ON dbo.bound_devices (user_ref_id, status);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_dev_tenant_status' AND object_id = OBJECT_ID(N'dbo.bound_devices'))
    CREATE INDEX IX_dev_tenant_status ON dbo.bound_devices (tenant_id, status);
GO

-- auth_challenges
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_chal_expires' AND object_id = OBJECT_ID(N'dbo.auth_challenges'))
    CREATE INDEX IX_chal_expires ON dbo.auth_challenges (expires_at);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_chal_tenant_status' AND object_id = OBJECT_ID(N'dbo.auth_challenges'))
    CREATE INDEX IX_chal_tenant_status ON dbo.auth_challenges (tenant_id, status);
GO

-- audit_log
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_audit_tenant_user_time' AND object_id = OBJECT_ID(N'dbo.audit_log'))
    CREATE INDEX IX_audit_tenant_user_time ON dbo.audit_log (tenant_id, user_ref_id, created_at);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_audit_time' AND object_id = OBJECT_ID(N'dbo.audit_log'))
    CREATE INDEX IX_audit_time ON dbo.audit_log (created_at);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_audit_tenant_type_time' AND object_id = OBJECT_ID(N'dbo.audit_log'))
    CREATE INDEX IX_audit_tenant_type_time ON dbo.audit_log (tenant_id, event_type, created_at);
GO

-- tenant_app_bindings（db-schema.md 第 9 節 / DB17）
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_appbind_tenant_status' AND object_id = OBJECT_ID(N'dbo.tenant_app_bindings'))
    CREATE INDEX IX_appbind_tenant_status ON dbo.tenant_app_bindings (tenant_id, status);
GO

-- signing_keys（db-schema.md 第 10 節 / DB18）
-- Filtered unique index：全表同時最多一把 status='ACTIVE' 金鑰。
-- 用途一：語意約束（隨時只有一把正在用來簽發的金鑰）。
-- 用途二：多實例首次啟動的並發競態防護——並發 INSERT ACTIVE 時只有一個成功，
--         其餘撞此唯一索引失敗，改讀既有金鑰，避免產生多把互斥的 JWKS 金鑰。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_signkey_one_active' AND object_id = OBJECT_ID(N'dbo.signing_keys'))
    CREATE UNIQUE INDEX UX_signkey_one_active ON dbo.signing_keys (status) WHERE status = 'ACTIVE';
GO

PRINT N'003_create_indexes.sql 執行完成：次要索引已建立/確認存在。';
GO
