<#
.SYNOPSIS
    真正啟動兩個獨立 JVM 行程（fido-server + shopping-site-reference），透過真實 HTTP 呼叫
    跑一次完整的跨行程端對端（E2E）驗證：註冊 -> 登入 -> JWT 驗證 -> 裝置管理 -> IDOR 反例。

.DESCRIPTION
    把原本手動執行的四個步驟自動化成一個可重複執行的 regression check：
      1. 分別在 fido-server / shopping-site-reference 兩個 Maven 模組執行
         `mvn -DskipTests package`，產生可執行的 fat jar。
      2. 在 fido-server 執行 `mvn -DskipTests test-compile`，把
         `com.fido.server.e2e.CrossProcessE2EManualRunner`（跨行程 E2E harness，重用
         WebAuthnCeremonyFixtures / TestKeyAttestationFixtures 組出真實密碼學合法的
         attestation/assertion）與其依賴的 test fixtures 一併編譯。
      3. 用 `mvn dependency:build-classpath` 解析執行 harness 所需的完整 classpath
         （main classes + test classes + 所有 compile/runtime/test scope 依賴，含
         jackson-dataformat-cbor、bcprov/bcpkix 等 harness 組 CBOR attestationObject 需要
         的套件）。
      4. 執行 harness（harness 本身會以個別 JVM 行程啟動 fido-server / shopping-site-reference，
         等待兩者皆可透過 HTTP 存活探測後，跑完整組真實跨行程流程，並在自己的 finally 區塊
         負責停止兩個子行程）。
      5. 印出 harness 的 PASS/FAIL 結果總表，並以 harness 的結束碼作為本腳本的結束碼。

    Harness 本身已經在自己的 try/finally 內妥善處理「不論成功或失敗都要停止 fido-server /
    shopping-site-reference 這兩個子行程」；本腳本額外加一層保險：不論本腳本本身在建置階段
    或執行階段失敗於何處，finally 區塊都會再檢查一次 8443（fido-server）與
    shopping-site-reference 埠號（預設 18081，對齊 application.yml 目前的預設值）是否還有
    行程在監聽，若有（例如 harness 本身被中途中斷、來不及跑到它自己的 finally），會強制
    停止該行程，避免留下殘留的伺服器行程佔用埠號。刻意只鎖定這兩個特定埠號，不會誤殺本機
    其他無關服務（例如舊版 shopping-site-reference 預設埠 8081 上長期佔用的 macmnsvc.exe，
    見 shopping-site-reference/src/main/resources/application.yml 註解）。

    本腳本本身是「執行既有 harness 的殼」，不重新實作任何跨行程流程邏輯；流程本身的
    詳細步驟/驗證項目見 fido-server/src/test/java/com/fido/server/e2e/CrossProcessE2EManualRunner.java
    的 Javadoc 與程式碼。

.PARAMETER SkipBuild
    跳過 `mvn package`/`mvn test-compile`/`mvn dependency:build-classpath` 這幾個建置步驟，
    直接沿用兩個模組現有的 target/ 產物執行 harness。僅供「確定程式碼沒改、只是想重跑一次
    E2E」時加速用；預設（不加此參數）一律重新建置，確保這是「跑最新程式碼」的 regression
    check，而不是可能跑到舊 jar 的過期結果。

.PARAMETER FidoPort
    fido-server 監聽埠號，預設 8443（對齊 fido-server/src/main/resources/application.yml）。

.PARAMETER ShopPort
    shopping-site-reference 監聽埠號，預設 18081（對齊
    shopping-site-reference/src/main/resources/application.yml 目前預設值）。harness 會以
    `--server.port=<ShopPort>` 明確指定，不依賴 shopping-site-reference 自己的預設值，
    故此參數與 application.yml 的預設值理論上可以不同，但保持一致比較不容易混淆。

.EXAMPLE
    powershell -File scripts\run-cross-process-e2e.ps1

.EXAMPLE
    # 程式碼沒改，只是想再跑一次確認結果穩定：
    powershell -File scripts\run-cross-process-e2e.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [int]$FidoPort = 8443,
    [int]$ShopPort = 18081
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$FidoServerDir = Join-Path $RepoRoot 'fido-server'
$ShopDir = Join-Path $RepoRoot 'shopping-site-reference'
$FidoJar = Join-Path $FidoServerDir 'target\fido-server-1.0.0.jar'
$ShopJar = Join-Path $ShopDir 'target\shopping-site-reference-1.0.0.jar'
$ClasspathFile = Join-Path $FidoServerDir 'target\cross-process-e2e-classpath.txt'
$RunnerMainClass = 'com.fido.server.e2e.CrossProcessE2EManualRunner'

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host "==== $Message ====" -ForegroundColor Cyan
}

function Invoke-MavenOrFail {
    param(
        [string]$WorkingDirectory,
        [string[]]$MavenArgs,
        [string]$StepDescription
    )
    Write-Step $StepDescription
    Push-Location $WorkingDirectory
    try {
        & mvn @MavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "$StepDescription 失敗（mvn exit code $LASTEXITCODE，於 $WorkingDirectory）"
        }
    } finally {
        Pop-Location
    }
}

# 額外保險：不論本腳本在哪個階段失敗，都再確認一次這兩個特定埠號沒有殘留行程在監聽
# （harness 自己的 finally 已經會停止它啟動的子行程；這裡是 belt-and-suspenders，只鎖定
# 本腳本關心的兩個埠號，不動本機其他無關服務）。
function Stop-ProcessListeningOnPort {
    param([int]$Port)
    try {
        $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    } catch {
        $conns = $null
    }
    if (-not $conns) {
        return
    }
    $procIds = $conns | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $procIds) {
        try {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            $procName = if ($proc) { $proc.ProcessName } else { '(unknown)' }
            Write-Host "[cleanup] port $Port still has a listening process (PID=$procId, $procName), force stopping." -ForegroundColor Yellow
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        } catch {
            Write-Host "[cleanup] failed to stop process on port $Port (PID=$procId): $_" -ForegroundColor Yellow
        }
    }
}

# harness 每次執行都會在 fido-server/poc-trust-roots/ 底下寫一個一次性測試 root PEM
# （e2e-manual-run-root.pem，內容隨機、每次執行都不同）；只清掉這個特定檔名，刻意不清整個
# 目錄，避免動到目錄內其他已進版控、Android PoC 沿用的 root 憑證
# （見 fido-server/poc-trust-roots/emulator-fido_poc_avd-droid-unregistered-device-ca.pem）。
function Remove-GeneratedTestRootPem {
    $pem = Join-Path $FidoServerDir 'poc-trust-roots\e2e-manual-run-root.pem'
    if (Test-Path $pem) {
        Remove-Item $pem -Force -ErrorAction SilentlyContinue
    }
}

$overallExitCode = 1
try {
    if (-not $SkipBuild) {
        Invoke-MavenOrFail -WorkingDirectory $FidoServerDir `
            -MavenArgs @('-q', '-DskipTests', 'package') `
            -StepDescription 'Build fido-server jar (mvn -DskipTests package)'

        Invoke-MavenOrFail -WorkingDirectory $ShopDir `
            -MavenArgs @('-q', '-DskipTests', 'package') `
            -StepDescription 'Build shopping-site-reference jar (mvn -DskipTests package)'

        Invoke-MavenOrFail -WorkingDirectory $FidoServerDir `
            -MavenArgs @('-q', '-DskipTests', 'test-compile') `
            -StepDescription 'Compile fido-server test sources (harness + WebAuthn/attestation fixtures)'

        Invoke-MavenOrFail -WorkingDirectory $FidoServerDir `
            -MavenArgs @('-q', 'dependency:build-classpath', "-Dmdep.outputFile=$ClasspathFile") `
            -StepDescription 'Resolve classpath needed to run the harness (mvn dependency:build-classpath)'
    } else {
        Write-Step 'SkipBuild specified: skipping mvn package / test-compile / build-classpath, reusing existing target/ artifacts.'
    }

    foreach ($f in @($FidoJar, $ShopJar, $ClasspathFile)) {
        if (-not (Test-Path $f)) {
            throw "Required file not found: $f (if using -SkipBuild, run once without it first to build)"
        }
    }

    Write-Step 'Run cross-process E2E harness (java -cp ... CrossProcessE2EManualRunner)'
    $classes = Join-Path $FidoServerDir 'target\classes'
    $testClasses = Join-Path $FidoServerDir 'target\test-classes'
    $depClasspath = (Get-Content $ClasspathFile -Raw).Trim()
    $fullClasspath = "$classes;$testClasses;$depClasspath"

    Push-Location $RepoRoot
    try {
        # 這兩個 -D 參數必須加引號：PowerShell 對「未加引號、含句點的 -Dxxx.yyy=value」token
        # 有拆解 bug（親測會把 -Dstdout.encoding=UTF-8 拆成 -Dstdout 與 .encoding=UTF-8 兩個
        # 獨立 argv，導致 java 誤把 .encoding=UTF-8 當成主類別名稱、真正的 mainClass 反而沒被
        # 傳進去），加上引號後 PowerShell 會把整個字串當一個 token 原封不動傳給 java，才不會被拆開。
        & java "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" -cp $fullClasspath $RunnerMainClass
        $overallExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} catch {
    Write-Host ''
    Write-Host "[run-cross-process-e2e] Unexpected exception: $_" -ForegroundColor Red
    $overallExitCode = 1
} finally {
    Write-Step 'Cleanup (safety net: make sure fido-server / shopping-site-reference ports have no leftover process)'
    Stop-ProcessListeningOnPort -Port $FidoPort
    Stop-ProcessListeningOnPort -Port $ShopPort
    Remove-GeneratedTestRootPem
}

Write-Host ''
if ($overallExitCode -eq 0) {
    Write-Host '[run-cross-process-e2e] Overall result: all checks passed.' -ForegroundColor Green
} else {
    Write-Host "[run-cross-process-e2e] Overall result: at least one check failed or an error occurred (exit code $overallExitCode). See output above." -ForegroundColor Red
}
exit $overallExitCode
