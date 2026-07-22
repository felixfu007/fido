/*
==============================================================================
 005_scheduled_backups.sql
 建立 SQL Server Agent Job：完整備份（週）+ 差異備份（日）+ 交易記錄備份（每 30 分鐘）

 對齊：CLAUDE.md「加密/備份：TDE 全庫加密 + 標準定期備份」；
 容量目標為中小規模（數萬會員、峰值 <=100 TPS），資料量預期不大，故採業界常見的
 「完整＋差異＋記錄」三層備份，兼顧備份時間、還原步驟數與 RPO：
   - 完整備份：每週日 01:00（範例；備份耗時隨資料量成長，屆時可再調整頻率）
   - 差異備份：每日 02:00（週日當天差異備份會近乎空白，屬正常現象，不影響還原鏈）
   - 交易記錄備份：每日 00:00-23:59，每 30 分鐘一次（RPO 上限約 30 分鐘，可依實際
     可接受的資料遺失範圍調整間隔，數值愈小 RPO 愈短但記錄備份檔案數量愈多）

 還原鏈：最近一次完整備份 + 最近一次差異備份（如有）+ 其後所有交易記錄備份，依序 RESTORE。

 【重要】新資料庫在設為 FULL 復原模式後，第一次交易記錄備份必須「晚於」第一次完整備份，
 否則記錄備份會失敗（LSN 鏈未建立）。請於 001/002/003/004 執行完成後，立即手動執行一次
 完整備份（可直接執行下方 FidoServerDb - Full Backup Job 一次，或手動下達 BACKUP DATABASE），
 再讓排程接手，避免排程尚未觸發前的空窗期完全沒有備份。

 前置需求：
   - SQL Server Agent 服務必須為「執行中」且設定為自動啟動，否則所有排程都不會觸發。
   - 執行者需有建立 Agent Job 的權限（一般為 sysadmin，或 msdb 的 SQLAgentOperatorRole 等，
     視環境的權限設計而定；建置階段建議直接以 sysadmin 執行）。
   - 備份目的地路徑（本檔範例為 D:\SQLBackup\FidoServerDb\...）請依實際環境調整，並建議
     使用與資料庫檔案不同的實體磁碟/儲存裝置，理想情況下另有異地/異機複本（例如排程另行
     將備份檔同步到備援儲存或網路芳鄰／NAS，此部分不在本檔案範圍內，建議另以排程或指令
     腳本處理）。
   - 備份檔保留天數（清理舊備份檔）本檔未包含（詳見檔尾說明），建議另訂備份檔案清理機制。

 冪等性：每個 Job 建立前先刪除同名既有 Job，可重複執行本檔以重建/修正排程設定。

 注意：下方各 Job 的 @owner_login_name 設為 N'sa'，此為多數環境的預設 sysadmin
 登入名稱；若目標環境已停用/重新命名 sa 帳號（常見的資安強化作法），請將所有
 @owner_login_name 改成實際存在且具 sysadmin 權限的登入帳號，否則 sp_add_job
 會因找不到該登入而失敗。
==============================================================================
*/

USE msdb;
GO
SET NOCOUNT ON;
GO

------------------------------------------------------------------------------
-- Job 1：完整備份（每週日 01:00）
------------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM msdb.dbo.sysjobs WHERE name = N'FidoServerDb - Full Backup')
    EXEC msdb.dbo.sp_delete_job @job_name = N'FidoServerDb - Full Backup';
GO

DECLARE @jobId BINARY(16);

EXEC msdb.dbo.sp_add_job
    @job_name = N'FidoServerDb - Full Backup',
    @enabled = 1,
    @description = N'FidoServerDb 每週完整備份，WITH COMPRESSION, CHECKSUM。',
    @category_name = N'Database Maintenance',
    @owner_login_name = N'sa',
    @job_id = @jobId OUTPUT;

EXEC msdb.dbo.sp_add_jobstep
    @job_id = @jobId,
    @step_name = N'Run Full Backup',
    @subsystem = N'TSQL',
    @database_name = N'FidoServerDb',
    @command = N'
DECLARE @path NVARCHAR(400) =
    N''D:\SQLBackup\FidoServerDb\FULL\FidoServerDb_FULL_''
    + REPLACE(REPLACE(CONVERT(NVARCHAR(19), SYSUTCDATETIME(), 126), '':'', ''-''), ''T'', ''_'')
    + N''.bak'';

BACKUP DATABASE [FidoServerDb]
TO DISK = @path
WITH COMPRESSION, CHECKSUM, INIT, STATS = 10, NAME = N''FidoServerDb-Full'';
',
    @retry_attempts = 3,
    @retry_interval = 5,
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'FidoServerDb - Weekly Sunday 01:00',
    @freq_type = 8,                -- Weekly
    @freq_interval = 1,            -- 1 = Sunday
    @freq_recurrence_factor = 1,   -- every 1 week
    @active_start_time = 010000;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'FidoServerDb - Full Backup',
    @schedule_name = N'FidoServerDb - Weekly Sunday 01:00';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'FidoServerDb - Full Backup',
    @server_name = N'(local)';
GO

------------------------------------------------------------------------------
-- Job 2：差異備份（每日 02:00）
------------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM msdb.dbo.sysjobs WHERE name = N'FidoServerDb - Differential Backup')
    EXEC msdb.dbo.sp_delete_job @job_name = N'FidoServerDb - Differential Backup';
GO

DECLARE @jobId BINARY(16);

EXEC msdb.dbo.sp_add_job
    @job_name = N'FidoServerDb - Differential Backup',
    @enabled = 1,
    @description = N'FidoServerDb 每日差異備份，WITH COMPRESSION, CHECKSUM。',
    @category_name = N'Database Maintenance',
    @owner_login_name = N'sa',
    @job_id = @jobId OUTPUT;

EXEC msdb.dbo.sp_add_jobstep
    @job_id = @jobId,
    @step_name = N'Run Differential Backup',
    @subsystem = N'TSQL',
    @database_name = N'FidoServerDb',
    @command = N'
DECLARE @path NVARCHAR(400) =
    N''D:\SQLBackup\FidoServerDb\DIFF\FidoServerDb_DIFF_''
    + REPLACE(REPLACE(CONVERT(NVARCHAR(19), SYSUTCDATETIME(), 126), '':'', ''-''), ''T'', ''_'')
    + N''.bak'';

BACKUP DATABASE [FidoServerDb]
TO DISK = @path
WITH DIFFERENTIAL, COMPRESSION, CHECKSUM, INIT, STATS = 10, NAME = N''FidoServerDb-Diff'';
',
    @retry_attempts = 3,
    @retry_interval = 5,
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'FidoServerDb - Daily 02:00',
    @freq_type = 4,                -- Daily
    @freq_interval = 1,            -- every 1 day
    @active_start_time = 020000;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'FidoServerDb - Differential Backup',
    @schedule_name = N'FidoServerDb - Daily 02:00';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'FidoServerDb - Differential Backup',
    @server_name = N'(local)';
GO

------------------------------------------------------------------------------
-- Job 3：交易記錄備份（每 30 分鐘）
------------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM msdb.dbo.sysjobs WHERE name = N'FidoServerDb - Log Backup')
    EXEC msdb.dbo.sp_delete_job @job_name = N'FidoServerDb - Log Backup';
GO

DECLARE @jobId BINARY(16);

EXEC msdb.dbo.sp_add_job
    @job_name = N'FidoServerDb - Log Backup',
    @enabled = 1,
    @description = N'FidoServerDb 交易記錄備份，每 30 分鐘一次，縮短 RPO 並控制記錄檔成長。',
    @category_name = N'Database Maintenance',
    @owner_login_name = N'sa',
    @job_id = @jobId OUTPUT;

EXEC msdb.dbo.sp_add_jobstep
    @job_id = @jobId,
    @step_name = N'Run Log Backup',
    @subsystem = N'TSQL',
    @database_name = N'FidoServerDb',
    @command = N'
IF EXISTS (
    SELECT 1 FROM sys.databases
    WHERE name = N''FidoServerDb'' AND recovery_model_desc = N''FULL''
      AND log_reuse_wait_desc <> N''NOTHING''  -- 粗略防呆：資料庫存在且非剛建立未使用
)
BEGIN
    DECLARE @path NVARCHAR(400) =
        N''D:\SQLBackup\FidoServerDb\LOG\FidoServerDb_LOG_''
        + REPLACE(REPLACE(CONVERT(NVARCHAR(19), SYSUTCDATETIME(), 126), '':'', ''-''), ''T'', ''_'')
        + N''.trn'';

    BACKUP LOG [FidoServerDb]
    TO DISK = @path
    WITH COMPRESSION, CHECKSUM, INIT, STATS = 25, NAME = N''FidoServerDb-Log'';
END
',
    @retry_attempts = 3,
    @retry_interval = 2,
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'FidoServerDb - Every 30 Minutes',
    @freq_type = 4,                -- Daily
    @freq_interval = 1,
    @freq_subday_type = 4,         -- Minutes
    @freq_subday_interval = 30,
    @active_start_time = 000000,
    @active_end_time = 235959;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'FidoServerDb - Log Backup',
    @schedule_name = N'FidoServerDb - Every 30 Minutes';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'FidoServerDb - Log Backup',
    @server_name = N'(local)';
GO

PRINT N'005_scheduled_backups.sql 執行完成：完整/差異/記錄備份 Job 已建立。請務必立即手動觸發一次 FidoServerDb - Full Backup Job（或手動 BACKUP DATABASE），確保還原鏈從有效的完整備份開始。';
GO

/*
------------------------------------------------------------------------------
 備份檔清理（未含於本檔的可執行邏輯，僅說明建議做法）：
 - 建議保留策略示例：FULL 保留最近 5 份（約 5 週）、DIFF 保留最近 14 天、LOG 保留最近 8 天，
   實際天數請依可用儲存空間與稽核/災難復原政策調整。
 - SQL Server 內建的 xp_delete_file（Maintenance Plan「清除維護」工作內部使用的擴充預存
   程序）可用來依副檔名與日期刪除舊備份檔，但它是「未公開文件化」的擴充預存程序，行為
   可能隨版本改變，建議在正式環境使用前先於測試環境驗證，或改用 Maintenance Plan 精靈
   （圖形介面）產生的清除工作，或以作業系統層級的排程工作（如 Windows工作排程器 +
   PowerShell/robocopy 腳本）取代，避免依賴未公開文件化的行為。
   範例（未驗證，僅供參考，執行前請先在測試環境確認語法可用）：
     EXEC master.dbo.xp_delete_file 0, N'D:\SQLBackup\FidoServerDb\FULL\', N'bak',
          N'2026-06-01T00:00:00', 0;
------------------------------------------------------------------------------
*/
