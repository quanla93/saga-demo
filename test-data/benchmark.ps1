param(
    [string]$ConfigPath = "test-data/benchmark-config.json",
    [string[]]$Engines,
    [switch]$SkipK6
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ConfigPath)) {
    throw "Benchmark config not found: $ConfigPath"
}

$config = Get-Content $ConfigPath -Raw | ConvertFrom-Json
$enginesToRun = if ($Engines -and $Engines.Count -gt 0) { $Engines } else { $config.engines }
$resultDir = [string]$config.resultDirectory
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$rows = New-Object System.Collections.Generic.List[string]

foreach ($engine in $enginesToRun) {
    $engineName = $engine.ToString().ToUpperInvariant()
    Write-Host "==> Switching stock engine to $engineName"
    Invoke-RestMethod -Method Post -Uri "$($config.inventoryServiceUrl)/admin/stock-engine/$engineName" | Out-Null

    if ($engineName -eq "REDIS") {
        try {
            Invoke-RestMethod -Method Post -Uri "$($config.inventoryServiceUrl)/admin/stock-engine/warm-cache" | Out-Null
        } catch {
            Write-Warning "Redis warm-cache endpoint failed or is unavailable: $($_.Exception.Message)"
        }
    }

    $summaryPath = Join-Path $resultDir "k6-summary-$engineName-$timestamp.json"
    $rawPath = Join-Path $resultDir "k6-raw-$engineName-$timestamp.json"
    $exitCode = 0

    if ($SkipK6) {
        Write-Host "Skipping k6 for $engineName"
        Set-Content -Path $summaryPath -Value "{}" -Encoding utf8
    } else {
        Write-Host "==> Running k6 for $engineName"
        $env:ORDER_BASE_URL = $config.orderServiceUrl
        $env:K6_SUMMARY_PATH = $summaryPath
        & k6 run --out "json=$rawPath" $config.k6Script
        $exitCode = $LASTEXITCODE
    }

    Write-Host "==> Waiting $($config.settleSeconds)s for async saga settlement"
    Start-Sleep -Seconds ([int]$config.settleSeconds)

    $rows.Add("| $engineName | `$summaryPath` | $exitCode | Raw: `$rawPath` |")
}

$template = Get-Content "test-data/benchmark-report-template.md" -Raw
$report = $template.Replace("{{generatedAt}}", (Get-Date).ToString("o"))
$report = $report.Replace("{{k6Script}}", [string]$config.k6Script)
$report = $report.Replace("{{engines}}", ($enginesToRun -join ", "))
$report = $report.Replace("{{orderServiceUrl}}", [string]$config.orderServiceUrl)
$report = $report.Replace("{{inventoryServiceUrl}}", [string]$config.inventoryServiceUrl)
$report = $report.Replace("{{rows}}", ($rows -join [Environment]::NewLine))

$reportPath = Join-Path $resultDir "benchmark-report-$timestamp.md"
Set-Content -Path $reportPath -Value $report -Encoding utf8
Write-Host "Benchmark report written to $reportPath"
