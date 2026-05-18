# Test data + runnable scenarios

Five artifacts covering smoke -> load -> stress. Pick the one matching the
question you want answered:

| File | Tool | Concurrency it can drive | Question it answers |
| --- | --- | --- | --- |
| `requests.http` | IntelliJ / VS Code REST Client | 1 at a time | "Does this specific scenario work?" |
| `run-scenarios.ps1` | PowerShell | 1 at a time, serial | "Does every scenario pass after my last change?" (smoke / CI gate) |
| `load-test.ps1` | PowerShell ThreadJobs | ~50-200 concurrent | "Does the stock engine survive parallel buyers without overselling?" |
| `k6-smoke.js` | k6 | 5 VUs / 30s | Fast pass/fail check before a stress run |
| `k6-stress.js` | k6 | Ramps to 1000 VUs over 4 min | "Where's the throughput ceiling? p95/p99 under load? When does the system break?" |

All artifacts assume:
- `docker compose up -d` is running (Postgres + Kafka + Redis + Kafdrop)
- All four services are up: order (8081), payment (8082), inventory (8083), ui (8080)
- Flyway has applied V3 so SKU-005 / SKU-006 / SKU-007 exist

## Install k6 (only needed for the .js scripts)

```powershell
winget install k6
# alternative: choco install k6
# alternative: scoop install k6
```

Verify: `k6 version`

## Seed catalogue (after V1 + V2 + V3)

| SKU | Name | Stock | Use |
| --- | --- | --- | --- |
| SKU-001 | Notebook | 100 | Happy-path workhorse |
| SKU-002 | Mechanical Keyboard | 20 | Generic |
| SKU-003 | Wireless Mouse | 50 | Generic |
| SKU-004 | Monitor 27" | 5 | Cheap way to push order total past 5000 fail-threshold |
| SKU-005 | Flash Sale GPU | 10 | **Contention test** — small stock makes overselling bugs visible |
| SKU-006 | Coffee Mug (bulk) | 10000 | High-volume happy path (k6 stress hammers this) |
| SKU-007 | Limited Hoodie | 200 | Medium-volume race tests |

## Quick recipes

```powershell
# 1. Smoke — does every scenario still pass?
pwsh test-data/run-scenarios.ps1

# 2. Contention check (50-200 concurrent fighting one SKU)
pwsh test-data/load-test.ps1                   # REDIS engine (default)
pwsh test-data/load-test.ps1 -Mode DATABASE    # pessimistic-lock — visibly slower
pwsh test-data/load-test.ps1 -N 500 -Each 1    # heavier contention

# 3. Throughput + latency curve under k6
k6 run test-data/k6-smoke.js                   # 30s sanity check
k6 run test-data/k6-stress.js                  # full ramp to 1000 VUs, ~4 min
k6 run --out json=results.json test-data/k6-stress.js   # capture raw data
```

## How to read k6-stress.js results

After the run, k6 prints a custom summary block:

```
========== Saga Demo Stress Test ==========
Orders accepted (201)      : 18432
Business error rate        : 0.34%
HTTP error rate            : 0.21%
Throughput (req/s avg)     : 76.8
Latency p50 / p95 / p99 ms : 42 / 287 / 1054
===========================================
```

What to look for:

- **Throughput plateau** — graph it from `results.json`. If req/s flatlines at
  ~80 even when VUs climb from 200 to 1000, you've found the ceiling
  (likely DB connection pool or single-instance order-service CPU).
- **p99 degradation curve** — the gap between p50 and p99 widens as you push
  past the ceiling. A 10x gap (50ms vs 500ms) means tail-latency amplification
  from contention or GC pauses.
- **Little's Law sanity check** — at steady state: `concurrency = throughput * latency`.
  If you ran 500 VUs and got 100 req/s with p50 of 4000ms, that's `100 * 4 = 400`
  ≈ 500 — consistent. If the math is wildly off, you're probably measuring during
  ramp-up, not steady state.
- **REDIS vs DATABASE** — flip the inventory engine via `POST /admin/stock-engine/{REDIS|DATABASE}`
  and re-run. The DATABASE run's p99 will balloon on contended SKUs because
  reservations queue behind row-level locks.

## What each script asserts about correctness

- **`run-scenarios.ps1`**: PASS/FAIL per scenario (happy, idempotency replay,
  compensating with stock restored, oversell rejected, engine flip both
  directions). Non-zero exit on any failure.
- **`load-test.ps1`**: stock conservation — `stock taken == confirmed orders × quantity`.
  Catches overselling bugs in either engine.
- **`k6-smoke.js` / `k6-stress.js`**: threshold gates on `http_req_failed`,
  `http_req_duration p(95)`, and a custom `business_errors` rate. k6 exits
  non-zero if any threshold is violated.

## When `load-test.ps1` stops being enough

PowerShell ThreadJobs are fine up to ~200 concurrent. Beyond that:
- The harness itself becomes the bottleneck (job scheduler latency)
- Memory per ThreadJob is high (each spawns a runspace)
- No built-in latency percentile tracking — you'd have to roll your own

That's when you switch to k6 — its single-process VU model handles tens of
thousands of concurrent users from one laptop.
