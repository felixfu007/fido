<#
==============================================================================
 007_backup_retention_and_offsite_sync.ps1

 【這是範本，不是可以直接排程執行的成品】
 正式使用前，請務必先讀完本檔說明並依實際環境調整下方「參數區」的數值
 （尤其是保留期數字與路徑），確認無誤後再排程執行。不要在沒看過內容的情況下
 直接排入 Windows工作排程器。

 用途：接續 infra/sql/005_scheduled_backups.sql 建立的完整/差異/交易記錄備份
 Job，處理該檔 README/檔尾註解中明確標示「未包含在 005 內」的兩件事：
   1. 舊備份檔清理（依保留期刪除超過期限的本機 .bak / .trn 檔案）
   2. 異地備援同步（把備份檔複製到另一個實體位置／機房）

 為什麼是 PowerShell 而不是 T-SQL：
   005_scheduled_backups.sql 檔尾已明確記載，SQL Server 內建的 xp_delete_file
   是「未公開文件化」的擴充預存程序，行為可能隨版本改變，不建議依賴；同一份
   檔案與 infra/sql/README.md 的「待人工拍板的開放問題」都建議改用 Windows
   工作排程器 + PowerShell/robocopy 處理備份檔案生命週期。本檔即依此建議實作，
   刻意不放進 infra/sql/ 的 T-SQL 編號序列（001-006 是「連到 SQL Server 執行的
   建置/排程 DDL 與系統預存程序呼叫」，本檔是「作業系統層級的檔案生命週期管理」，
   性質不同、不使用 sqlcmd/SSMS 執行，故獨立放在 infra/scripts/）。

 對齊：
   - CLAUDE.md「加密/備份：TDE 全庫加密 + 標準定期備份」
   - docs/vendor/maintenance-guide.md §2.3「備份檔異地與清理」的最低保留建議：
     FULL 保留 5 份、DIFF 保留 14 天、LOG 保留 8 天（皆已做成下方可調整參數，
     不是寫死值；正式環境請依可用儲存空間與稽核/災難復原政策調整）。
   - 備份來源路徑預設對齊 005_scheduled_backups.sql 範例路徑
     （D:\SQLBackup\FidoServerDb\FULL|DIFF|LOG），若該檔已依實際環境改過路徑，
     本檔的 -FullBackupDir / -DiffBackupDir / -LogBackupDir 參數也要同步改。

 安全設計（避免被誤當成可以不看內容直接執行的東西）：
   - 預設 -DryRun 為 $true：只會印出「將會做什麼」，不會真的刪除或複製任何檔案。
     確認預覽結果沒問題後，需明確加上 -DryRun:$false 才會真正執行。
   - 每個刪除/複製動作都會先印出來源、目的地、判斷依據（保留期/份數）。
   - 不使用 robocopy /MIR（鏡像，會把目的地多出的檔案刪掉）；異地端改用與本機
     相同邏輯的「依保留期各自清理」，避免鏡像模式把異地當成單向鏡子、不慎連動
     刪除異地既有備份。

 使用方式範例（在 PowerShell 中，具備讀寫來源/目的地路徑權限的帳號下執行）：

   # 先乾跑，確認會刪什麼、會同步什麼，不會真的動任何檔案
   .\007_backup_retention_and_offsite_sync.ps1

   # 確認無誤後，真正執行（仍建議先在測試路徑驗證過一次）
   .\007_backup_retention_and_offsite_sync.ps1 -DryRun:$false

   # 覆寫路徑與保留期（依實際環境調整，以下數值僅為示範）
   .\007_backup_retention_and_offsite_sync.ps1 `
       -FullBackupDir 'D:\SQLBackup\FidoServerDb\FULL' `
       -DiffBackupDir 'D:\SQLBackup\FidoServerDb\DIFF' `
       -LogBackupDir  'D:\SQLBackup\FidoServerDb\LOG' `
       -FullRetentionCount 5 `
       -DiffRetentionDays 14 `
       -LogRetentionDays 8 `
       -OffsiteDestinationRoot '\\backup-site\FidoServerDb' `
       -OffsiteRetentionMultiplier 2 `
       -DryRun:$false

 建議排程方式（範本，未在本檔內自動註冊，請視環境決定是否採用）：
   Windows 工作排程器 → 每日觸發一次（建議排在 005 的每日差異備份、當日最後一次
   交易記錄備份「之後」，例如每日 03:45，避開備份寫入時段），動作設為執行
   powershell.exe，參數帶 -File "<本檔路徑>" -DryRun:$false（以及視需要覆寫的
   路徑/保留期參數）。範例（僅供參考，未實際註冊，需自行依環境調整帳號權限）：

     $action  = New-ScheduledTaskAction -Execute 'powershell.exe' `
                  -Argument '-NoProfile -File "D:\path\to\007_backup_retention_and_offsite_sync.ps1" -DryRun:$false'
     $trigger = New-ScheduledTaskTrigger -Daily -At 03:45AM
     Register-ScheduledTask -TaskName 'FidoServerDb - Backup Retention And Offsite Sync' `
       -Action $action -Trigger $trigger -RunLevel Highest -Description '刪除逾期備份檔並同步到異地'
==============================================================================
#>

[CmdletBinding()]
param(
    # ---- 備份來源路徑（請對齊 infra/sql/005_scheduled_backups.sql 實際使用的路徑）----
    [string] $FullBackupDir = 'D:\SQLBackup\FidoServerDb\FULL',
    [string] $DiffBackupDir = 'D:\SQLBackup\FidoServerDb\DIFF',
    [string] $LogBackupDir  = 'D:\SQLBackup\FidoServerDb\LOG',

    # ---- 本機保留策略（可調整，以下為 maintenance-guide.md §2.3 的最低保留建議值）----
    # FULL：依「份數」保留（每份體積較大、頻率低，用份數比用天數直覺）
    [int] $FullRetentionCount = 5,
    # DIFF／LOG：依「天數」保留
    [int] $DiffRetentionDays = 14,
    [int] $LogRetentionDays  = 8,

    # ---- 異地備援目的地（範本層級：一個可寫入的路徑即可，可以是另一棟機房的
    #      網路磁碟/NAS 路徑，也可以替換成其他異地儲存的掛載路徑。本檔不整合任何
    #      特定雲端 SDK——全地端部署專案，異地通常指另一個實體位置的儲存）----
    [string] $OffsiteDestinationRoot = '\\CHANGE-ME-offsite-host\FidoServerDb\Backup',

    # 異地保留期＝本機保留期 x 此倍數（異地通常希望比本機留更久，供更長時間的
    # 災難復原窗口；預設 2 倍，可依實際需求調整，設 1 則與本機保留期相同）
    [double] $OffsiteRetentionMultiplier = 2,

    # 預設只預覽、不動任何檔案。確認無誤後手動加 -DryRun:$false 才會真正執行。
    [bool] $DryRun = $true
)

$ErrorActionPreference = 'Stop'

function Write-Section {
    param([string] $Title)
    Write-Host ''
    Write-Host "==== $Title ====" -ForegroundColor Cyan
}

function Test-DirectoryExists {
    param([string] $Path, [string] $Label)
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Warning "$Label 路徑不存在，略過：$Path（若這是預期中尚未產生任何備份的全新環境可忽略；否則請確認路徑是否正確）"
        return $false
    }
    return $true
}

<#
    依「保留份數」清理：只保留最新 N 個檔案，其餘刪除。
    適合 FULL 備份（頻率低、每份重要，用「留幾份」比用「留幾天」直覺）。
#>
function Remove-OldBackupFilesByCount {
    param(
        [string] $Directory,
        [string] $Filter,
        [int] $KeepCount,
        [bool] $DryRun
    )
    if (-not (Test-DirectoryExists -Path $Directory -Label $Filter)) { return }

    $files = Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Sort-Object LastWriteTime -Descending

    if ($files.Count -le $KeepCount) {
        Write-Host "[$Directory] 現有 $($files.Count) 個 $Filter 檔案，未超過保留份數 $KeepCount，無需清理。"
        return
    }

    $toDelete = $files | Select-Object -Skip $KeepCount
    Write-Host "[$Directory] 現有 $($files.Count) 個 $Filter 檔案，保留最新 $KeepCount 份，將清理 $($toDelete.Count) 個舊檔案："
    foreach ($f in $toDelete) {
        if ($DryRun) {
            Write-Host "  [DRYRUN] 會刪除：$($f.FullName)（LastWriteTime=$($f.LastWriteTime)）"
        } else {
            Write-Host "  刪除：$($f.FullName)（LastWriteTime=$($f.LastWriteTime)）"
            Remove-Item -LiteralPath $f.FullName -Force
        }
    }
}

<#
    依「保留天數」清理：刪除 LastWriteTime 早於 (今天 - RetentionDays) 的檔案。
    適合 DIFF／LOG 備份（頻率高、單份重要性隨時間遞減，用「留幾天」較直覺）。
#>
function Remove-OldBackupFilesByAge {
    param(
        [string] $Directory,
        [string] $Filter,
        [int] $RetentionDays,
        [bool] $DryRun
    )
    if (-not (Test-DirectoryExists -Path $Directory -Label $Filter)) { return }

    $cutoff = (Get-Date).AddDays(-1 * $RetentionDays)
    $files = Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Where-Object { $_.LastWriteTime -lt $cutoff }

    if (-not $files -or $files.Count -eq 0) {
        Write-Host "[$Directory] 沒有早於 $cutoff（保留 $RetentionDays 天）的 $Filter 檔案，無需清理。"
        return
    }

    Write-Host "[$Directory] 將清理 $($files.Count) 個早於 $cutoff（保留 $RetentionDays 天）的 $Filter 檔案："
    foreach ($f in $files) {
        if ($DryRun) {
            Write-Host "  [DRYRUN] 會刪除：$($f.FullName)（LastWriteTime=$($f.LastWriteTime)）"
        } else {
            Write-Host "  刪除：$($f.FullName)（LastWriteTime=$($f.LastWriteTime)）"
            Remove-Item -LiteralPath $f.FullName -Force
        }
    }
}

<#
    以 robocopy 把來源目錄的備份檔同步到異地目的地。
    刻意不用 /MIR（鏡像）：/MIR 在來源刪除舊檔後，下一次同步會把異地對應的檔案
    也一併刪除，等於把「本機保留期」錯誤地套用到異地備份上。這裡改用 /E（含子
    目錄）+ /XO（來源檔案較新或目的地不存在才複製，已存在且沒更新的略過）達成
    「只增量搬新檔案過去、不因本機刪除而連動刪除異地檔案」，異地自己的保留期
    由下方 Remove-OldBackupFilesByAge/ByCount 對異地目錄另外處理。
#>
function Sync-ToOffsite {
    param(
        [string] $SourceDir,
        [string] $DestDir,
        [bool] $DryRun
    )
    if (-not (Test-DirectoryExists -Path $SourceDir -Label '同步來源')) { return }

    if ($DestDir -like '*CHANGE-ME*') {
        Write-Warning "OffsiteDestinationRoot 仍是預留佔位字串（$DestDir），請改成實際的異地路徑後再執行同步。已略過本次同步。"
        return
    }

    $robocopyArgs = @($SourceDir, $DestDir, '/E', '/XO', '/R:3', '/W:5', '/NP')
    if ($DryRun) {
        $robocopyArgs += '/L'   # /L：僅列出會做的動作，不實際複製（robocopy 原生的乾跑模式）
        Write-Host "[DRYRUN] robocopy $($robocopyArgs -join ' ')"
    } else {
        Write-Host "robocopy $($robocopyArgs -join ' ')"
    }

    & robocopy.exe @robocopyArgs
    # robocopy 的 exit code 0-7 都算成功（0=無檔案需複製，1=有檔案複製成功，
    # 2/4=有額外/不匹配檔案但非失敗，3/5/6/7 為以上組合）；>=8 才是真的失敗。
    if ($LASTEXITCODE -ge 8) {
        Write-Warning "robocopy 回傳碼 $LASTEXITCODE（>=8 視為失敗），請檢查來源/目的地路徑與網路連線、權限。"
    } else {
        Write-Host "robocopy 完成，回傳碼 $LASTEXITCODE（0-7 皆視為正常，非錯誤）。"
    }
}

# ==============================================================================
# 主流程
# ==============================================================================

Write-Host '本檔為範本腳本；如尚未確認參數即執行，請先用預設的 -DryRun (=$true) 預覽，不會動到任何檔案。'
if ($DryRun) {
    Write-Host '目前模式：DRY RUN（僅預覽，不會刪除或複製任何檔案）。加上 -DryRun:$false 才會真正執行。' -ForegroundColor Yellow
} else {
    Write-Host '目前模式：正式執行（會實際刪除舊檔案、實際同步到異地）。' -ForegroundColor Red
}

# ---- 步驟 1：先同步到異地，再清理本機舊檔，避免「先刪本機、異地卻還沒拿到最新檔」的空窗 ----
Write-Section '步驟 1：同步備份檔到異地'
Sync-ToOffsite -SourceDir $FullBackupDir -DestDir (Join-Path $OffsiteDestinationRoot 'FULL') -DryRun $DryRun
Sync-ToOffsite -SourceDir $DiffBackupDir -DestDir (Join-Path $OffsiteDestinationRoot 'DIFF') -DryRun $DryRun
Sync-ToOffsite -SourceDir $LogBackupDir  -DestDir (Join-Path $OffsiteDestinationRoot 'LOG')  -DryRun $DryRun

# ---- 步驟 2：清理本機超過保留期的舊備份檔 ----
Write-Section '步驟 2：清理本機舊備份檔'
Remove-OldBackupFilesByCount -Directory $FullBackupDir -Filter '*.bak' -KeepCount $FullRetentionCount -DryRun $DryRun
Remove-OldBackupFilesByAge   -Directory $DiffBackupDir -Filter '*.bak' -RetentionDays $DiffRetentionDays -DryRun $DryRun
Remove-OldBackupFilesByAge   -Directory $LogBackupDir  -Filter '*.trn' -RetentionDays $LogRetentionDays  -DryRun $DryRun

# ---- 步驟 3：清理異地端超過（本機保留期 x 倍數）的舊備份檔，避免異地無限累積 ----
Write-Section '步驟 3：清理異地舊備份檔'
$offsiteFullKeepCount = [int]([math]::Ceiling($FullRetentionCount * $OffsiteRetentionMultiplier))
$offsiteDiffKeepDays  = [int]([math]::Ceiling($DiffRetentionDays * $OffsiteRetentionMultiplier))
$offsiteLogKeepDays   = [int]([math]::Ceiling($LogRetentionDays  * $OffsiteRetentionMultiplier))

if ($OffsiteDestinationRoot -like '*CHANGE-ME*') {
    Write-Warning 'OffsiteDestinationRoot 仍是預留佔位字串，略過異地清理步驟。'
} else {
    Remove-OldBackupFilesByCount -Directory (Join-Path $OffsiteDestinationRoot 'FULL') -Filter '*.bak' -KeepCount $offsiteFullKeepCount -DryRun $DryRun
    Remove-OldBackupFilesByAge   -Directory (Join-Path $OffsiteDestinationRoot 'DIFF') -Filter '*.bak' -RetentionDays $offsiteDiffKeepDays -DryRun $DryRun
    Remove-OldBackupFilesByAge   -Directory (Join-Path $OffsiteDestinationRoot 'LOG')  -Filter '*.trn' -RetentionDays $offsiteLogKeepDays  -DryRun $DryRun
}

Write-Section '完成'
if ($DryRun) {
    Write-Host '本次為 DRY RUN，以上僅為預覽結果，未實際刪除或複製任何檔案。確認無誤後請加上 -DryRun:$false 正式執行。' -ForegroundColor Yellow
} else {
    Write-Host '本次為正式執行，以上動作已實際完成。' -ForegroundColor Green
}
