# Saga Demo — load test: N concurrent orders racing for the same hot SKU.
#
# Demonstrates the difference between the DATABASE engine (pessimistic-lock
# serialization) and the REDIS engine (atomic Lua). Run twice — once in each
# mode — and compare total wall-clock + reservation success counts.
#
# Run:    pwsh test-data/load-test.ps1                  # default 100 orders, 1 GPU each
#         pwsh test-data/load-test.ps1 -N 200 -Each 1   # 200 orders racing for 10 GPUs
#         pwsh test-data/load-test.ps1 -Mode DATABASE   # flip engine, then load test

param(
    [int]$N = 100,
    [int]$Each = 1,
    [ValidateSet('REDIS', 'DATABASE', 'KEEP')]
    [string]$Mode = 'KEEP'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Order     = 'http://localhost:8081'
$Inventory = 'http://localhost:8083'

# Use SKU-005 (Flash Sale GPU) — seeded at 10 units. Tunable, but small stock
# makes the contention obvious without spamming logs.
$Gpu      = '55555555-5555-5555-5555-555555555555'
$Customer = '00000000-0000-0000-0000-000000000099'

if ($Mode -ne 'KEEP') {
    Write-Host "Switching engine to $Mode ..." -ForegroundColor Yellow
    Invoke-RestMethod -Method Post -Uri "$Inventory/admin/stock-engine/$Mode" | Out-Null
}
$currentMode = (Invoke-RestMethod -Uri "$Inventory/admin/stock-engine").effectiveMode

$initialStock = (Invoke-RestMethod -Uri "$Inventory/api/products" |
                  Where-Object { $_.id -eq $Gpu }).stockAvailable
Write-Host "Engine: $currentMode | SKU-005 stock before: $initialStock | firing $N orders x $Each unit each"
Write-Host ""

$sw = [System.Diagnostics.Stopwatch]::StartNew()

# Fire N requests in parallel via runspaces (much lighter than Start-Job).
$jobs = 1..$N | ForEach-Object {
    $i = $_
    Start-ThreadJob -ScriptBlock {
        param($Order, $Customer, $Gpu, $Each, $i)
        $key = "load-$i-$(New-Guid)"
        $body = @{
            customerId = $Customer
            items      = @(@{ productId = $Gpu; quantity = $Each; unitPrice = 50.00 })
        } | ConvertTo-Json -Depth 5
        try {
            $resp = Invoke-RestMethod -Method Post -Uri "$Order/api/orders" `
                    -ContentType 'application/json' `
                    -Headers @{ 'Idempotency-Key' = $key } -Body $body
            return [pscustomobject]@{ index = $i; orderId = $resp.orderId; error = $null }
        } catch {
            return [pscustomobject]@{ index = $i; orderId = $null; error = $_.Exception.Message }
        }
    } -ArgumentList $Order, $Customer, $Gpu, $Each, $i
}

$results = $jobs | Wait-Job | Receive-Job
$jobs    | Remove-Job

$submitMs = $sw.ElapsedMilliseconds

# Give the saga workers some time to drain.
Write-Host "All POSTs returned in $submitMs ms. Waiting up to 30s for sagas to terminate..."
$deadline = (Get-Date).AddSeconds(30)
$pending = $results | Where-Object { $_.orderId } | Select-Object -ExpandProperty orderId
$confirmed = 0
$cancelled = 0
while ((Get-Date) -lt $deadline -and ($confirmed + $cancelled) -lt $pending.Count) {
    $confirmed = 0; $cancelled = 0
    foreach ($oid in $pending) {
        $o = Invoke-RestMethod -Uri "$Order/api/orders/$oid"
        if ($o.sagaState -eq 'COMPLETED') { $confirmed++ }
        elseif ($o.sagaState -eq 'FAILED') { $cancelled++ }
    }
    if (($confirmed + $cancelled) -lt $pending.Count) { Start-Sleep -Milliseconds 500 }
}
$sw.Stop()

$finalStock = (Invoke-RestMethod -Uri "$Inventory/api/products" |
                Where-Object { $_.id -eq $Gpu }).stockAvailable
$httpErrors = ($results | Where-Object { $_.error }).Count

Write-Host ""
Write-Host "----- Results -----" -ForegroundColor Cyan
Write-Host ("Engine mode       : {0}" -f $currentMode)
Write-Host ("Submitted (POST)  : {0} ({1} HTTP errors)" -f $N, $httpErrors)
Write-Host ("Confirmed (saga)  : {0}" -f $confirmed)
Write-Host ("Cancelled (saga)  : {0}" -f $cancelled)
Write-Host ("Stock taken       : {0}  (initial {1} -> final {2})" -f ($initialStock - $finalStock), $initialStock, $finalStock)
Write-Host ("Submit wall-clock : {0} ms" -f $submitMs)
Write-Host ("End-to-end        : {0} ms" -f $sw.ElapsedMilliseconds)
Write-Host ""
Write-Host "Stock conservation check:" -NoNewline
if (($initialStock - $finalStock) -eq ($confirmed * $Each)) {
    Write-Host " OK (no overselling)" -ForegroundColor Green
} else {
    Write-Host " MISMATCH — investigate!" -ForegroundColor Red
}
