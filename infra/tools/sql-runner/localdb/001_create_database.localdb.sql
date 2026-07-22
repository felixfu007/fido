/*
==============================================================================
 001_create_database.localdb.sql

 這不是正式部署腳本，是 SQL Server LocalDB 開發環境專用的「建庫」變體，僅供
 devops-engineer / dev-engineer 在沒有正式 SQL Server 環境時，於本機 LocalDB
 上驗證 infra/sql/002_create_tables.sql、infra/sql/003_create_indexes.sql
 是否可正確執行。

 與正式版 infra/sql/001_create_database.sql 的差異（且僅有這些差異）：
   1. 不指定 FILENAME（D:\SQLData\... / E:\SQLLog\...）：
      - LocalDB 以目前使用者身分執行、無 sysadmin 權限，且本機未必有對應磁碟路徑
        （例如本機沒有 E: 磁碟），指定該路徑會導致 CREATE DATABASE 失敗。
      - 改為不指定路徑，交由 LocalDB 使用其預設資料檔位置（通常在使用者設定檔目錄下）。
   2. 拿掉「需要 sysadmin/dbcreator 權限」與磁碟路徑相關的前置需求說明（LocalDB 不適用）。
   3. COMPATIBILITY_LEVEL 改為 130（而非正式版的 150）：
      - 本機 LocalDB 引擎版本為 13.1.4001.0，即 SQL Server 2016，其相容性層級上限即為 130；
        若指定 150（SQL Server 2019）會直接報錯（超出此引擎版本支援範圍），並非架構決策的偏離，
        純粹是本機驗證引擎版本較舊所致。正式環境（SQL Server 2019 以上）仍以 150 為準，
        不受此檔影響。
   4. 其餘（RECOVERY FULL、READ_COMMITTED_SNAPSHOT、
      AUTO_CLOSE/AUTO_SHRINK/AUTO_UPDATE_STATISTICS/AUTO_CREATE_STATISTICS 等資料庫層級設定）
      與正式版逐項相同，未做任何簡化，確保 LocalDB 上驗證的資料庫設定與正式腳本一致。

 正式部署仍以 infra/sql/001_create_database.sql 為準，此檔不會、也不應該取代它。
==============================================================================
*/

SET NOCOUNT ON;
GO

IF DB_ID(N'FidoServerDb') IS NOT NULL
BEGIN
    PRINT N'資料庫 FidoServerDb 已存在，略過建立步驟。';
END
ELSE
BEGIN
    CREATE DATABASE FidoServerDb;
END
GO

ALTER DATABASE FidoServerDb SET RECOVERY FULL;
GO

ALTER DATABASE FidoServerDb SET COMPATIBILITY_LEVEL = 130;
GO

ALTER DATABASE FidoServerDb SET READ_COMMITTED_SNAPSHOT ON;
GO

ALTER DATABASE FidoServerDb SET AUTO_CLOSE OFF;
ALTER DATABASE FidoServerDb SET AUTO_SHRINK OFF;
ALTER DATABASE FidoServerDb SET AUTO_UPDATE_STATISTICS ON;
ALTER DATABASE FidoServerDb SET AUTO_CREATE_STATISTICS ON;
GO

PRINT N'001_create_database.localdb.sql 執行完成：FidoServerDb 資料庫已建立/確認存在，RECOVERY=FULL。';
GO
