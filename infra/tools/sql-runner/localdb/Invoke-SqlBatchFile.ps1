<#
.SYNOPSIS
    在沒有 sqlcmd.exe / Invoke-Sqlcmd 的環境中，執行含有 GO 批次分隔符號的 .sql 檔案。

.DESCRIPTION
    GO 是 SQLCMD / SSMS 專用的批次分隔符號，不是合法的 T-SQL 陳述式本身。本腳本會將
    每個 .sql 檔依「獨立一行、去除頭尾空白後等於 GO（忽略大小寫）」切割成多個批次，
    再依序透過 .NET System.Data.SqlClient（隨 Windows .NET Framework 內建，不需另外
    安裝任何套件）逐批次執行，並將 SQL Server 的 PRINT 訊息（透過 InfoMessage 事件）
    輸出到主控台，方便確認執行結果。

    為什麼不用 infra/tools/sql-runner（Java + Microsoft JDBC Driver for SQL Server）：
    經實測驗證，Microsoft JDBC Driver（純 Java Type 4 驅動）僅支援 TCP/IP 傳輸協定，
    完全沒有具名管道（named pipe）支援；而 SQL Server LocalDB 設計上僅監聽具名管道，
    不提供 TCP/IP 監聽（以 netstat/Get-NetTCPConnection 驗證 sqlservr.exe 進程無任何
    TCP 監聽埠，且 LocalDB 的機碼中也找不到 SuperSocketNetLib\Tcp 設定項，並非「預設關閉
    可手動開啟」，而是設計上就不提供）。因此 JDBC 一律無法連線 LocalDB，與連線字串寫法無關。
    本腳本改用 .NET SqlClient（Windows 內建、原生支援 LocalDB 的 "(localdb)\<instance>"
    定址語法），純粹作為 LocalDB 開發環境的替代執行工具；對「正式環境」（有 TCP 監聽的
    獨立 SQL Server 實例）仍建議使用 infra/tools/sql-runner（JDBC）或標準 sqlcmd。

.PARAMETER ConnectionString
    ADO.NET 連線字串，例如：
    "Server=(localdb)\MSSQLLocalDB;Database=master;Integrated Security=True;Connect Timeout=30;"

.PARAMETER SqlFiles
    要依序執行的 .sql 檔案路徑（陣列，依序執行）。

.EXAMPLE
    .\Invoke-SqlBatchFile.ps1 `
        -ConnectionString "Server=(localdb)\MSSQLLocalDB;Database=master;Integrated Security=True;" `
        -SqlFiles @(
            "D:\fido\infra\tools\sql-runner\localdb\001_create_database.localdb.sql",
            "D:\fido\infra\sql\002_create_tables.sql",
            "D:\fido\infra\sql\003_create_indexes.sql"
        )
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$ConnectionString,

    [Parameter(Mandatory = $true)]
    [string[]]$SqlFiles
)

Add-Type -AssemblyName 'System.Data' -ErrorAction Stop

function Split-ByGo {
    param([string]$Content)

    $batches = New-Object System.Collections.Generic.List[string]
    $current = New-Object System.Text.StringBuilder
    $lines = $Content -split "\r\n|\n|\r"
    foreach ($line in $lines) {
        if ($line.Trim() -match '(?i)^GO$') {
            $batches.Add($current.ToString())
            $current = New-Object System.Text.StringBuilder
        } else {
            [void]$current.AppendLine($line)
        }
    }
    if ($current.ToString().Trim().Length -gt 0) {
        $batches.Add($current.ToString())
    }
    return $batches
}

$conn = New-Object System.Data.SqlClient.SqlConnection
$conn.ConnectionString = $ConnectionString

# 把 PRINT 訊息（SQL Server 以 INFO 訊息傳回）接到主控台輸出。
$infoHandler = {
    param($sender, $e)
    foreach ($msg in $e.Errors) {
        Write-Host ("[PRINT] " + $msg.Message)
    }
}
Register-ObjectEvent -InputObject $conn -EventName InfoMessage -Action $infoHandler | Out-Null

Write-Host "[sql-runner-localdb] 連線中..."
$conn.Open()
Write-Host ("[sql-runner-localdb] 連線成功。SQL Server 版本: " + $conn.ServerVersion)

try {
    foreach ($file in $SqlFiles) {
        Write-Host ""
        Write-Host ("========== 執行檔案: " + $file + " ==========")
        $content = Get-Content -Path $file -Raw -Encoding UTF8
        $batches = Split-ByGo -Content $content
        Write-Host ("[sql-runner-localdb] 共切割出 " + $batches.Count + " 個批次。")

        for ($i = 0; $i -lt $batches.Count; $i++) {
            $batch = $batches[$i]
            if ($batch.Trim().Length -eq 0) { continue }
            $batchNo = $i + 1
            $cmd = $conn.CreateCommand()
            $cmd.CommandText = $batch
            $cmd.CommandTimeout = 120
            try {
                [void]$cmd.ExecuteNonQuery()
            } catch {
                Write-Host ("[sql-runner-localdb] 檔案 " + $file + " 第 " + $batchNo + " 個批次執行失敗:")
                Write-Host "---- 批次內容 ----"
                Write-Host $batch
                Write-Host "------------------"
                throw
            }
        }
        Write-Host ("========== 檔案完成: " + $file + " ==========")
    }
} finally {
    $conn.Close()
}

Write-Host ""
Write-Host "[sql-runner-localdb] 全部檔案執行完成。"
