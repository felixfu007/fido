$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName 'System.Data'

$connStr = "Server=(localdb)\MSSQLLocalDB;Database=FidoServerDb;Integrated Security=True;Connect Timeout=30;"
$conn = New-Object System.Data.SqlClient.SqlConnection
$conn.ConnectionString = $connStr
$conn.Open()

function Run-Query {
    param([string]$Title, [string]$Sql)
    Write-Host ""
    Write-Host "===== $Title ====="
    $cmd = $conn.CreateCommand()
    $cmd.CommandText = $Sql
    $reader = $cmd.ExecuteReader()
    $cols = @()
    for ($i = 0; $i -lt $reader.FieldCount; $i++) { $cols += $reader.GetName($i) }
    Write-Host ($cols -join " | ")
    $rowCount = 0
    while ($reader.Read()) {
        $vals = @()
        for ($i = 0; $i -lt $reader.FieldCount; $i++) {
            $v = $reader.GetValue($i)
            if ($v -eq [System.DBNull]::Value) { $v = "NULL" }
            $vals += [string]$v
        }
        Write-Host ($vals -join " | ")
        $rowCount++
    }
    $reader.Close()
    Write-Host "-- row count: $rowCount --"
}

# 0. all 9 tables present
Run-Query -Title "All tables in dbo schema" -Sql @"
SELECT t.name AS table_name
FROM sys.tables t
WHERE t.schema_id = SCHEMA_ID('dbo')
ORDER BY t.name;
"@

# 1. cross_device_sessions columns (含 issued_jwt)
Run-Query -Title "cross_device_sessions columns" -Sql @"
SELECT c.name AS column_name, ty.name AS data_type, c.max_length, c.is_nullable
FROM sys.columns c
JOIN sys.types ty ON c.user_type_id = ty.user_type_id
WHERE c.object_id = OBJECT_ID('dbo.cross_device_sessions')
ORDER BY c.column_id;
"@

# 2. issued_jwt column specifically (缺口二：NVARCHAR(4000) NULL)
Run-Query -Title "issued_jwt column detail" -Sql @"
SELECT c.name AS column_name, ty.name AS data_type, c.max_length AS max_length_bytes,
       (c.max_length / 2) AS max_length_chars, c.is_nullable
FROM sys.columns c
JOIN sys.types ty ON c.user_type_id = ty.user_type_id
WHERE c.object_id = OBJECT_ID('dbo.cross_device_sessions') AND c.name = 'issued_jwt';
"@

# 3. PK
Run-Query -Title "cross_device_sessions PRIMARY KEY" -Sql @"
SELECT kc.name AS constraint_name, kc.type_desc, c.name AS column_name
FROM sys.key_constraints kc
JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE kc.parent_object_id = OBJECT_ID('dbo.cross_device_sessions') AND kc.type = 'PK';
"@

# 4. UNIQUE constraints
Run-Query -Title "cross_device_sessions UNIQUE constraints" -Sql @"
SELECT kc.name AS constraint_name, c.name AS column_name, ic.key_ordinal
FROM sys.key_constraints kc
JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE kc.parent_object_id = OBJECT_ID('dbo.cross_device_sessions') AND kc.type = 'UQ'
ORDER BY kc.name, ic.key_ordinal;
"@

# 5. CHECK constraints
Run-Query -Title "cross_device_sessions CHECK constraints" -Sql @"
SELECT name AS constraint_name, definition
FROM sys.check_constraints
WHERE parent_object_id = OBJECT_ID('dbo.cross_device_sessions');
"@

# 6. FK
Run-Query -Title "cross_device_sessions FOREIGN KEYs" -Sql @"
SELECT
    fk.name AS fk_name,
    OBJECT_NAME(fk.parent_object_id) AS parent_table,
    pc.name AS parent_column,
    OBJECT_NAME(fk.referenced_object_id) AS ref_table,
    rc.name AS ref_column
FROM sys.foreign_keys fk
JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
WHERE fk.parent_object_id = OBJECT_ID('dbo.cross_device_sessions')
ORDER BY fk.name;
"@

# 7. Indexes (含 003_create_indexes.sql 的兩個次要索引)
Run-Query -Title "cross_device_sessions indexes" -Sql @"
SELECT i.name AS index_name, i.type_desc, i.is_unique,
    STUFF((SELECT ', ' + c.name
           FROM sys.index_columns ic
           JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
           WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
           ORDER BY ic.key_ordinal
           FOR XML PATH('')), 1, 2, '') AS columns
FROM sys.indexes i
WHERE i.object_id = OBJECT_ID('dbo.cross_device_sessions') AND i.name IS NOT NULL
ORDER BY i.name;
"@

# 8. Specific indexes from 003
Run-Query -Title "IX_xdev_tenant_status / IX_xdev_expires exist?" -Sql @"
SELECT name, type_desc, is_unique
FROM sys.indexes
WHERE object_id = OBJECT_ID('dbo.cross_device_sessions')
  AND name IN ('IX_xdev_tenant_status', 'IX_xdev_expires');
"@

$conn.Close()

# 9. Agent Jobs (006_retention_cleanup_jobs.sql -- 過期標記 + 清理)
# LocalDB 沒有 SQL Server Agent 服務（Job 不會自動排程執行），但 msdb.dbo.sysjobs/
# sysjobsteps/sysschedules 仍可用來確認 sp_add_job/sp_add_jobstep/sp_add_jobserver
# 建立的 Job 定義本身是否存在、內容是否與 006_retention_cleanup_jobs.sql 相符。
$msdbConnStr = "Server=(localdb)\MSSQLLocalDB;Database=msdb;Integrated Security=True;Connect Timeout=30;"
$msdbConn = New-Object System.Data.SqlClient.SqlConnection
$msdbConn.ConnectionString = $msdbConnStr
$msdbConn.Open()

function Run-MsdbQuery {
    param([string]$Title, [string]$Sql)
    Write-Host ""
    Write-Host "===== $Title ====="
    $cmd = $msdbConn.CreateCommand()
    $cmd.CommandText = $Sql
    $reader = $cmd.ExecuteReader()
    $cols = @()
    for ($i = 0; $i -lt $reader.FieldCount; $i++) { $cols += $reader.GetName($i) }
    Write-Host ($cols -join " | ")
    $rowCount = 0
    while ($reader.Read()) {
        $vals = @()
        for ($i = 0; $i -lt $reader.FieldCount; $i++) {
            $v = $reader.GetValue($i)
            if ($v -eq [System.DBNull]::Value) { $v = "NULL" }
            $vals += [string]$v
        }
        Write-Host ($vals -join " | ")
        $rowCount++
    }
    $reader.Close()
    Write-Host "-- row count: $rowCount --"
}

Run-MsdbQuery -Title "cross_device_sessions Agent Jobs present (no duplicates)" -Sql @"
SELECT name, COUNT(*) AS job_count
FROM sysjobs
WHERE name IN (N'FidoServerDb - Expire Cross-Device Sessions', N'FidoServerDb - Purge Old Cross-Device Sessions')
GROUP BY name
ORDER BY name;
"@

Run-MsdbQuery -Title "cross_device_sessions Agent Job steps + schedules" -Sql @"
SELECT j.name AS job_name, s.step_name, sch.name AS schedule_name,
       sch.freq_type, sch.freq_subday_type, sch.freq_subday_interval
FROM sysjobs j
JOIN sysjobsteps s ON s.job_id = j.job_id
LEFT JOIN sysjobschedules js ON js.job_id = j.job_id
LEFT JOIN sysschedules sch ON sch.schedule_id = js.schedule_id
WHERE j.name IN (N'FidoServerDb - Expire Cross-Device Sessions', N'FidoServerDb - Purge Old Cross-Device Sessions')
ORDER BY j.name;
"@

$msdbConn.Close()

Write-Host ""
Write-Host "Verification complete."
