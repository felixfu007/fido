/*
==============================================================================
 001_create_database.sql
 建立 FIDO 伺服器專用資料庫（獨立 SQL Server 實例）

 對齊：
   - CLAUDE.md：獨立 SQL Server 實例、TDE 全庫加密、中小規模（數萬會員、峰值 <=100 TPS）、全地端部署
   - docs/db-schema.md 第 1 節全域慣例

 前置需求：
   - 已安裝並可連線的 SQL Server 執行個體（建議 SQL Server 2019 Standard/Enterprise 以上；
     TDE 自 SQL Server 2019 起 Standard Edition 亦支援，之前版本僅 Enterprise 支援，請先確認目標版本）。
   - 執行者需有 sysadmin（或至少 dbcreator）權限。
   - 下列磁碟路徑僅為範例，正式部署前請依實際主機磁碟配置調整：
       資料檔（.mdf/.ndf）：D:\SQLData\FidoServerDb\
       交易記錄檔（.ldf）： E:\SQLLog\FidoServerDb\
     建議資料檔與記錄檔分別放在不同實體磁碟，以降低單一磁碟故障風險並提升 I/O 效能。

 執行方式：以 sqlcmd 或 SSMS 對目標 SQL Server 執行個體執行（非對特定資料庫，因為資料庫尚未存在）。
==============================================================================
*/

SET NOCOUNT ON;
GO

-- 資料庫名稱集中定義一次，若要更改名稱，全套 infra/sql 腳本需同步修改（皆使用 FidoServerDb 字面值）。
IF DB_ID(N'FidoServerDb') IS NOT NULL
BEGIN
    PRINT N'資料庫 FidoServerDb 已存在，略過建立步驟（如需重建請先確認並手動處理既有資料庫）。';
END
ELSE
BEGIN
    -- 路徑請依實際主機環境調整；若目標路徑不存在，CREATE DATABASE 會失敗，請先建立資料夾。
    CREATE DATABASE FidoServerDb
    ON PRIMARY
    (
        NAME = N'FidoServerDb_Data',
        FILENAME = N'D:\SQLData\FidoServerDb\FidoServerDb.mdf',
        SIZE = 1024MB,
        FILEGROWTH = 256MB
    )
    LOG ON
    (
        NAME = N'FidoServerDb_Log',
        FILENAME = N'E:\SQLLog\FidoServerDb\FidoServerDb.ldf',
        SIZE = 512MB,
        FILEGROWTH = 256MB
    );
END
GO

-- 回收模式設為 FULL：
--   1) 支援交易記錄備份（見 005_scheduled_backups.sql），將 RPO 縮短到分鐘等級。
--   2) 中小規模系統（數萬會員、峰值 <=100 TPS）資料量不大，FULL 模式的記錄檔成長可控。
-- 注意：資料庫改為 FULL 復原模式後，在第一次「完整備份」執行之前，行為仍近似 SIMPLE
-- （記錄檔會被自動截斷），必須盡快執行一次手動完整備份，交易記錄備份才會開始生效並縮短記錄鏈。
ALTER DATABASE FidoServerDb SET RECOVERY FULL;
GO

-- 相容性層級：請依實際安裝的 SQL Server 版本調整（此處以 SQL Server 2019 = 150 為預設示例）。
-- 如目標版本較新（如 2022 = 160），建議改用該版本對應數值以使用其最佳化功能。
ALTER DATABASE FidoServerDb SET COMPATIBILITY_LEVEL = 150;
GO

-- READ_COMMITTED_SNAPSHOT：降低讀寫互鎖機率，對 100 TPS 等級的併發讀寫有實質幫助且風險低，建議開啟。
ALTER DATABASE FidoServerDb SET READ_COMMITTED_SNAPSHOT ON;
GO

-- 其他建議的資料庫層級設定（穩健預設值，非強制，可依維運政策調整）：
ALTER DATABASE FidoServerDb SET AUTO_CLOSE OFF;
ALTER DATABASE FidoServerDb SET AUTO_SHRINK OFF;   -- 避免檔案反覆收縮/成長造成的效能與碎片問題
ALTER DATABASE FidoServerDb SET AUTO_UPDATE_STATISTICS ON;
ALTER DATABASE FidoServerDb SET AUTO_CREATE_STATISTICS ON;
GO

PRINT N'001_create_database.sql 執行完成：FidoServerDb 資料庫已建立/確認存在，RECOVERY=FULL。';
GO
