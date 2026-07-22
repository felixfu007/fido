/*
==============================================================================
 006_retention_cleanup_jobs.sql
 資料保留與清理排程（對應 docs/db-schema.md 第 10 節 DB11 / DB12）

 建立三個 SQL Server Agent Job：
   1. FidoServerDb - Expire Auth Challenges（每 1 分鐘）
      將已逾期但仍是 PENDING 的 auth_challenges 標記為 EXPIRED。
   2. FidoServerDb - Purge Old Auth Challenges（每日 03:15）
      實體刪除 created_at 超過 1 天的 auth_challenges 列（非稽核來源，短生命週期，
      僅保留近期少量供除錯）。
   3. FidoServerDb - AuditLog Retention Cleanup（每日 03:30）
      實體刪除 created_at 超過 365 天的 audit_log 列，對齊 CLAUDE.md「稽核紀錄保留 1 年」。

 設計決策（對照 docs/db-schema.md 第 10 節的訂正說明）：
   db-schema.md 第 10 節對 audit_log 清理提出兩個選項：(a) 每日以 DELETE 刪除超過
   365 天的列，或 (b) 依月分割（partition by 月）搭配 SWITCH + DROP 高效清理。
   本次實作選擇方案 (a)：以批次 DELETE 迴圈清理。理由：
     - CLAUDE.md 容量目標為中小規模（數萬會員、峰值 <=100 TPS），資料量與清理量
       都不大，分割表（partition function/scheme + 檔案群組）帶來的維運複雜度
       相對此規模而言是過度設計；本次任務指示亦明確要求「不用太複雜」。
     - 批次 DELETE（每批 5,000 列、批次間 WAITFOR DELAY 讓出鎖與 I/O）在此資料量級
       可於合理時間內完成，且 audit_log 已有 created_at 索引（IX_audit_time）
       可支援高效範圍掃描。
     - 若日後資料量顯著成長（例如稽核事件量遠超預期、DELETE 耗時開始影響營運），
       可再評估改採分割表方案；屆時屬於偏離目前容量目標的架構調整，應先與
       systems-analyst 確認再實作。

 前置需求：同 005_scheduled_backups.sql（SQL Agent 服務需執行中、建立者需有對應權限）。
 冪等性：每個 Job 建立前先刪除同名既有 Job。

 注意：同 005_scheduled_backups.sql，下方 @owner_login_name = N'sa' 為預設值，
 若目標環境 sa 帳號已停用/重新命名，請改成實際存在的 sysadmin 登入帳號。
==============================================================================
*/

USE msdb;
GO
SET NOCOUNT ON;
GO

------------------------------------------------------------------------------
-- Job 1：標記過期 challenge（每 1 分鐘）
------------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM msdb.dbo.sysjobs WHERE name = N'FidoServerDb - Expire Auth Challenges')
    EXEC msdb.dbo.sp_delete_job @job_name = N'FidoServerDb - Expire Auth Challenges';
GO

DECLARE @jobId BINARY(16);

EXEC msdb.dbo.sp_add_job
    @job_name = N'FidoServerDb - Expire Auth Challenges',
    @enabled = 1,
    @description = N'將逾時仍為 PENDING 的 auth_challenges 標記為 EXPIRED（docs/db-schema.md DB11）。',
    @category_name = N'Database Maintenance',
    @owner_login_name = N'sa',
    @job_id = @jobId OUTPUT;

EXEC msdb.dbo.sp_add_jobstep
    @job_id = @jobId,
    @step_name = N'Mark Expired Challenges',
    @subsystem = N'TSQL',
    @database_name = N'FidoServerDb',
    @command = N'
UPDATE dbo.auth_challenges
SET status = ''EXPIRED''
WHERE status = ''PENDING'' AND expires_at < SYSUTCDATETIME();
',
    @retry_attempts = 2,
    @retry_interval = 1,
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'FidoServerDb - Every 1 Minute',
    @freq_type = 4,                -- Daily
    @freq_interval = 1,
    @freq_subday_type = 4,         -- Minutes
    @freq_subday_interval = 1,
    @active_start_time = 000000,
    @active_end_time = 235959;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'FidoServerDb - Expire Auth Challenges',
    @schedule_name = N'FidoServerDb - Every 1 Minute';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'FidoServerDb - Expire Auth Challenges',
    @server_name = N'(local)';
GO

------------------------------------------------------------------------------
-- Job 2：清除超過 1 天的 auth_challenges（每日 03:15，批次刪除避免大交易鎖表）
------------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM msdb.dbo.sysjobs WHERE name = N'FidoServerDb - Purge Old Auth Challenges')
    EXEC msdb.dbo.sp_delete_job @job_name = N'FidoServerDb - Purge Old Auth Challenges';
GO

DECLARE @jobId BINARY(16);

EXEC msdb.dbo.sp_add_job
    @job_name = N'FidoServerDb - Purge Old Auth Challenges',
    @enabled = 1,
    @description = N'刪除 created_at 超過 1 天的 auth_challenges（非稽核來源，docs/db-schema.md DB11）。',
    @category_name = N'Database Maintenance',
    @owner_login_name = N'sa',
    @job_id = @jobId OUTPUT;

EXEC msdb.dbo.sp_add_jobstep
    @job_id = @jobId,
    @step_name = N'Purge Old Challenges (Batched)',
    @subsystem = N'TSQL',
    @database_name = N'FidoServerDb',
    @command = N'
SET NOCOUNT ON;
DECLARE @BatchSize INT = 5000;
DECLARE @RowsDeleted INT = 1;
DECLARE @CutOff DATETIME2(3) = DATEADD(DAY, -1, SYSUTCDATETIME());

WHILE @RowsDeleted > 0
BEGIN
    DELETE TOP (@BatchSize)
    FROM dbo.auth_challenges
    WHERE created_at < @CutOff;

    SET @RowsDeleted = @@ROWCOUNT;

    IF @RowsDeleted > 0
        WAITFOR DELAY ''00:00:00.200'';
END
',
    @retry_attempts = 2,
    @retry_interval = 5,
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'FidoServerDb - Daily 03:15',
    @freq_type = 4,
    @freq_interval = 1,
    @active_start_time = 031500;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'FidoServerDb - Purge Old Auth Challenges',
    @schedule_name = N'FidoServerDb - Daily 03:15';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'FidoServerDb - Purge Old Auth Challenges',
    @server_name = N'(local)';
GO

------------------------------------------------------------------------------
-- Job 3：audit_log 保留 1 年，超過即刪除（每日 03:30，批次刪除）
------------------------------------------------------------------------------
IF EXISTS (SELECT 1 FROM msdb.dbo.sysjobs WHERE name = N'FidoServerDb - AuditLog Retention Cleanup')
    EXEC msdb.dbo.sp_delete_job @job_name = N'FidoServerDb - AuditLog Retention Cleanup';
GO

DECLARE @jobId BINARY(16);

EXEC msdb.dbo.sp_add_job
    @job_name = N'FidoServerDb - AuditLog Retention Cleanup',
    @enabled = 1,
    @description = N'刪除 created_at 超過 365 天的 audit_log（對齊 CLAUDE.md 稽核保留 1 年，docs/db-schema.md DB12）。',
    @category_name = N'Database Maintenance',
    @owner_login_name = N'sa',
    @job_id = @jobId OUTPUT;

EXEC msdb.dbo.sp_add_jobstep
    @job_id = @jobId,
    @step_name = N'Purge AuditLog Beyond 365 Days (Batched)',
    @subsystem = N'TSQL',
    @database_name = N'FidoServerDb',
    @command = N'
SET NOCOUNT ON;
DECLARE @BatchSize INT = 5000;
DECLARE @RowsDeleted INT = 1;
DECLARE @CutOff DATETIME2(3) = DATEADD(DAY, -365, SYSUTCDATETIME());

WHILE @RowsDeleted > 0
BEGIN
    DELETE TOP (@BatchSize)
    FROM dbo.audit_log
    WHERE created_at < @CutOff;

    SET @RowsDeleted = @@ROWCOUNT;

    IF @RowsDeleted > 0
        WAITFOR DELAY ''00:00:00.200'';
END
',
    @retry_attempts = 2,
    @retry_interval = 5,
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'FidoServerDb - Daily 03:30',
    @freq_type = 4,
    @freq_interval = 1,
    @active_start_time = 033000;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'FidoServerDb - AuditLog Retention Cleanup',
    @schedule_name = N'FidoServerDb - Daily 03:30';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'FidoServerDb - AuditLog Retention Cleanup',
    @server_name = N'(local)';
GO

PRINT N'006_retention_cleanup_jobs.sql 執行完成：challenge 過期標記/清理、audit_log 1 年保留清理 Job 已建立。';
GO
