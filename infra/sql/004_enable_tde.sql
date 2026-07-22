/*
==============================================================================
 004_enable_tde.sql
 啟用 TDE（Transparent Data Encryption）全庫加密，並立即備份憑證與私密金鑰。

 對齊：CLAUDE.md「加密/備份：TDE 全庫加密 + 標準定期備份」。

 *** 最重要的一件事：憑證與私密金鑰遺失 = 加密的資料庫永遠無法還原/附加 ***
 本檔案第 4 步會立刻備份憑證與私密金鑰。備份完成後，請務必：
   1. 將備份出的 .cer（憑證）與 .pvk（私密金鑰，已用密碼加密）檔案，複製到
      「與資料庫伺服器不同」的安全儲存位置（例如異地保險櫃、公司金鑰保管系統、
      獨立的秘密管理系統），不要只留在資料庫伺服器本機磁碟。
   2. 私密金鑰加密密碼（@PvkPassword）與主金鑰密碼（@MasterKeyPassword）不要和
      憑證檔案放在一起，建議存放於密碼管理工具或實體保險櫃，並限制知悉人員。
   3. 之後若還原此資料庫到「另一台」SQL Server 執行個體（例如災難復原、遷移），
      必須先在目的地執行個體用同一份 .cer + .pvk + 密碼還原憑證，否則資料庫
      無法附加/還原（會出現 "cannot find server certificate" 類錯誤）。
   4. 建議每隔一段時間（例如每季）或每次憑證輪替後，演練一次「用備份的憑證在
      另一台測試機還原資料庫」，確認備份確實可用，而不是只信任備份「有跑成功」。

 前置需求：
   - 執行者需有 sysadmin 權限（CREATE MASTER KEY / CREATE CERTIFICATE / ALTER DATABASE
     ENCRYPTION 皆為高權限操作）。
   - 下列密碼與檔案路徑皆為【範例佔位】，正式執行前務必替換為正式環境的強密碼與
     實際的安全儲存路徑；密碼不應直接寫在腳本內留存於版控，此處僅為示範腳本，
     正式執行建議改用參數化方式輸入或搭配密碼管理工具。
==============================================================================
*/

SET NOCOUNT ON;
GO

------------------------------------------------------------------------------
-- 步驟 1：於 master 資料庫建立 Service Master Key 保護下的 Database Master Key
--         （若該執行個體 master 尚未有 DMK 才需要建立）
------------------------------------------------------------------------------
USE master;
GO

IF NOT EXISTS (SELECT 1 FROM sys.symmetric_keys WHERE name = N'##MS_DatabaseMasterKey##')
BEGIN
    -- !!! 請將下方密碼換成正式環境的強密碼，並依前述說明妥善保管 !!!
    CREATE MASTER KEY ENCRYPTION BY PASSWORD = N'REPLACE_WITH_STRONG_MASTER_KEY_PASSWORD_!23';
    PRINT N'master 資料庫的 Database Master Key 已建立。';
END
ELSE
BEGIN
    PRINT N'master 資料庫已存在 Database Master Key，略過建立。';
END
GO

------------------------------------------------------------------------------
-- 步驟 2：建立 TDE 用憑證（存放於 master，供 FidoServerDb 的 DEK 使用）
------------------------------------------------------------------------------
USE master;
GO

IF NOT EXISTS (SELECT 1 FROM sys.certificates WHERE name = N'TDE_FidoServerDb_Cert')
BEGIN
    CREATE CERTIFICATE TDE_FidoServerDb_Cert
    WITH SUBJECT = N'TDE certificate for FidoServerDb',
         EXPIRY_DATE = N'2036-12-31';  -- 建議設定明確到期日並建立到期前更新/輪替的提醒排程
    PRINT N'TDE 憑證 TDE_FidoServerDb_Cert 已建立。';
END
ELSE
BEGIN
    PRINT N'TDE 憑證 TDE_FidoServerDb_Cert 已存在，略過建立。';
END
GO

------------------------------------------------------------------------------
-- 步驟 3：於 FidoServerDb 建立 Database Encryption Key（DEK），並以上述憑證加密
------------------------------------------------------------------------------
USE FidoServerDb;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.dm_database_encryption_keys
    WHERE database_id = DB_ID(N'FidoServerDb')
)
BEGIN
    CREATE DATABASE ENCRYPTION KEY
    WITH ALGORITHM = AES_256
    ENCRYPTION BY SERVER CERTIFICATE TDE_FidoServerDb_Cert;
    PRINT N'FidoServerDb 的 Database Encryption Key 已建立（AES_256）。';
END
ELSE
BEGIN
    PRINT N'FidoServerDb 已存在 Database Encryption Key，略過建立。';
END
GO

------------------------------------------------------------------------------
-- 步驟 4：【關鍵】立即備份憑證與私密金鑰 —— 務必在啟用 TDE 之前或之後立刻執行，不可省略
------------------------------------------------------------------------------
USE master;
GO

-- 備份路徑僅為範例，請改為實際的安全備份路徑（且建議備份後另外複製一份到異地）。
-- !!! 請將下方私密金鑰加密密碼換成正式環境的強密碼，並與主金鑰密碼分開保管 !!!
BACKUP CERTIFICATE TDE_FidoServerDb_Cert
TO FILE = N'D:\SQLSecure\TDE\TDE_FidoServerDb_Cert.cer'
WITH PRIVATE KEY
(
    FILE = N'D:\SQLSecure\TDE\TDE_FidoServerDb_Cert.pvk',
    ENCRYPTION BY PASSWORD = N'REPLACE_WITH_STRONG_PVK_PASSWORD_!45'
);
GO

PRINT N'憑證與私密金鑰已備份至 D:\SQLSecure\TDE\。請立即將這兩個檔案複製到與此資料庫伺服器不同的安全儲存位置，並回報備份完成狀態。';
GO

------------------------------------------------------------------------------
-- 步驟 5：啟用 TDE（全庫加密）
------------------------------------------------------------------------------
USE master;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.dm_database_encryption_keys dek
    JOIN sys.databases d ON d.database_id = dek.database_id
    WHERE d.name = N'FidoServerDb' AND dek.encryption_state = 3  -- 3 = Encrypted
)
BEGIN
    ALTER DATABASE FidoServerDb SET ENCRYPTION ON;
    PRINT N'已對 FidoServerDb 發出 ENCRYPTION ON 指令，加密為背景非同步程序，需輪詢確認完成（見下方驗證查詢）。';
END
ELSE
BEGIN
    PRINT N'FidoServerDb 已處於加密狀態，略過。';
END
GO

------------------------------------------------------------------------------
-- 驗證查詢：確認加密狀態。encryption_state = 3 代表已完成加密（Encrypted）。
-- 加密程序視資料量與磁碟效能需要一段時間，非立即完成，請輪詢直到 percent_complete = 0
-- 且 encryption_state = 3。
------------------------------------------------------------------------------
SELECT
    d.name                    AS database_name,
    dek.encryption_state,
    CASE dek.encryption_state
        WHEN 0 THEN N'No database encryption key present, no encryption'
        WHEN 1 THEN N'Unencrypted'
        WHEN 2 THEN N'Encryption in progress'
        WHEN 3 THEN N'Encrypted'
        WHEN 4 THEN N'Key change in progress'
        WHEN 5 THEN N'Decryption in progress'
        WHEN 6 THEN N'Protection change in progress'
        ELSE N'Unknown'
    END                        AS encryption_state_desc,
    dek.percent_complete,
    dek.encryptor_type,
    c.name                     AS certificate_name,
    c.expiry_date              AS certificate_expiry_date
FROM sys.dm_database_encryption_keys dek
JOIN sys.databases d ON d.database_id = dek.database_id
LEFT JOIN sys.certificates c ON c.thumbprint = dek.encryptor_thumbprint
WHERE d.name = N'FidoServerDb';
GO
