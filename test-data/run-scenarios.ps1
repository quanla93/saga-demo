# Saga Demo — end-to-end scenario runner with assertions.
#
# Prerequisites: all four services running locally + docker-compose up.
# Run:    pwsh test-data/run-scenarios.ps1
#
# Each scenario prints PASS/FAIL. Exits non-zero on first failure so CI can
# treat this script as a smoke test.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Order     = 'http://localhost:8081'
$Inventory = 'http://localhost:8083'

$Customer1 = '00000000-0000-0000-0000-000000000001'
$Customer2 = '00000000-0000-0000-0000-000000000002'

$Notebook = '11111111-1111-1111-1111-111111111111'
$Monitor  = '44444444-4444-4444-4444-444444444444'
$Gpu      = '55555555-5555-5555-5555-555555555555'
$Mug      = '66666666-6666-6666-6666-666666666666'

$script:Failed = 0
$script:Passed = 0

function Step($name) { Write-Host "`n=== $name ===" -ForegroundColor Cyan }
function Pass($msg)  { Write-Host "  PASS  $msg" -ForegroundColor Green; $script:Passed++ }
function Fail($msg)  { Write-Host "  FAIL  $msg" -ForegroundColor Red;   $script:Failed++ }

function Assert([bool]$cond, [string]$msg) {
    if ($cond) { Pass $msg } else { Fail $msg }
}

# Poll until saga reaches a terminal state or timeout.
function Wait-ForTerminal([string]$orderId, [int]$timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $o = Invoke-RestMethod -Uri "$Order/api/orders/$orderId"
        if ($o.sagaState -eq 'COMPLETED' -or $o.sagaState -eq 'FAILED') { return $o }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for $orderId to terminate"
}

function New-Order([string]$customer, [string]$productId, [int]$qty, [decimal]$price, [string]$key) {
    $body = @{
        customerId = $customer
        items = @(@{ productId = $productId; quantity = $qty; unitPrice = $price })
    } | ConvertTo-Json -Depth 5
    return Invoke-RestMethod -Method Post -Uri "$Order/api/orders" `
        -ContentType 'application/json' -Headers @{ 'Idempotency-Key' = $key } -Body $body
}

# ----------------------------------------------------------------------

Step '1. Sanity: services are up'
try {
    $products = Invoke-RestMethod -Uri "$Inventory/api/products"
    $mode     = Invoke-RestMethod -Uri "$Inventory/admin/stock-engine"
    Assert ($products.Count -gt 0)         "inventory-service reachable, $($products.Count) products"
    Assert ($mode.effectiveMode -ne $null) "admin endpoint reachable, mode=$($mode.effectiveMode)"
} catch {
    Fail "Sanity check failed: $_"
    Write-Host "Make sure docker-compose + 4 services are running." -ForegroundColor Yellow
    exit 1
}

# ----------------------------------------------------------------------

Step '2. Happy path: 2 notebooks @ 100 each -> COMPLETED'
$key = "scen-happy-$(New-Guid)"
$created = New-Order $Customer1 $Notebook 2 100.00 $key
Assert ($created.status -eq 'PENDING')          "initial status PENDING"
$final = Wait-ForTerminal $created.orderId
Assert ($final.status -eq 'CONFIRMED')          "final order status CONFIRMED"
Assert ($final.sagaState -eq 'COMPLETED')       "saga state COMPLETED"
$happyOrderId = $created.orderId

# ----------------------------------------------------------------------

Step '3. Idempotency: same key -> same orderId, no second saga'
$replay = New-Order $Customer1 $Notebook 2 100.00 $key
Assert ($replay.orderId -eq $happyOrderId)      "replay returned the same orderId"

# ----------------------------------------------------------------------

Step '4. Compensating path: 2 monitors @ 3000 each (total 6000 > 5000) -> FAILED + stock restored'
$beforeMonitor = (Invoke-RestMethod -Uri "$Inventory/api/products" |
                   Where-Object { $_.id -eq $Monitor }).stockAvailable
$failKey = "scen-fail-$(New-Guid)"
$created = New-Order $Customer2 $Monitor 2 3000.00 $failKey
$final = Wait-ForTerminal $created.orderId
Assert ($final.status -eq 'CANCELLED')          "order CANCELLED"
Assert ($final.sagaState -eq 'FAILED')          "saga FAILED"
Assert ($final.failureReason -ne $null)         "failureReason populated"
$afterMonitor = (Invoke-RestMethod -Uri "$Inventory/api/products" |
                  Where-Object { $_.id -eq $Monitor }).stockAvailable
Assert ($afterMonitor -eq $beforeMonitor)        "monitor stock restored ($beforeMonitor -> $afterMonitor)"

# ----------------------------------------------------------------------

Step '5. Insufficient stock: 11 GPUs (only 10 available) -> FAILED at reservation, no compensate needed'
$created = New-Order $Customer1 $Gpu 11 50.00 "scen-oversell-$(New-Guid)"
$final = Wait-ForTerminal $created.orderId
Assert ($final.sagaState -eq 'FAILED')           "saga FAILED at inventory step"
Assert ($final.failureReason -like '*nsufficient*')  "reason mentions insufficient stock"

# ----------------------------------------------------------------------

Step '6. Engine flip: REDIS -> DATABASE -> REDIS, both modes process orders'
$initial = (Invoke-RestMethod -Uri "$Inventory/admin/stock-engine").effectiveMode

$null = Invoke-RestMethod -Method Post -Uri "$Inventory/admin/stock-engine/DATABASE"
$now  = (Invoke-RestMethod -Uri "$Inventory/admin/stock-engine").effectiveMode
Assert ($now -eq 'DATABASE')                     "mode flipped to DATABASE"

$dbOrder = New-Order $Customer1 $Mug 1 5.00 "scen-db-$(New-Guid)"
$final = Wait-ForTerminal $dbOrder.orderId
Assert ($final.status -eq 'CONFIRMED')           "order processed under DATABASE engine"

$null = Invoke-RestMethod -Method Post -Uri "$Inventory/admin/stock-engine/REDIS"
$now  = (Invoke-RestMethod -Uri "$Inventory/admin/stock-engine").effectiveMode
Assert ($now -eq 'REDIS')                        "mode flipped back to REDIS"

$rdOrder = New-Order $Customer1 $Mug 1 5.00 "scen-rd-$(New-Guid)"
$final = Wait-ForTerminal $rdOrder.orderId
Assert ($final.status -eq 'CONFIRMED')           "order processed under REDIS engine"

# Leave the engine in whatever mode it started in.
$null = Invoke-RestMethod -Method Post -Uri "$Inventory/admin/stock-engine/$initial"

# ----------------------------------------------------------------------

Write-Host "`n----------------------------------------"
Write-Host ("Passed: {0}    Failed: {1}" -f $Passed, $Failed) `
           -ForegroundColor $(if ($Failed -eq 0) { 'Green' } else { 'Red' })
if ($Failed -gt 0) { exit 1 }
