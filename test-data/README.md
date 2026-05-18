# Test data + runnable scenarios

Three artifacts, pick whichever matches how you want to drive the demo:

| File | Use it when |
| --- | --- |
| `requests.http` | You're in IntelliJ or VS Code with the REST Client / HTTP Client extension and want to click individual scenarios |
| `run-scenarios.ps1` | You want a single command that fires every scenario in order and prints PASS/FAIL — also good as a smoke test in CI |
| `load-test.ps1` | You want to stress-test contention on a hot SKU and compare DATABASE vs REDIS engines |

All three assume:
- `docker compose up -d` is running (Postgres + Kafka + Redis + Kafdrop)
- All four services are up: order (8081), payment (8082), inventory (8083), ui (8080)
- Flyway has applied V3 so SKU-005 / SKU-006 / SKU-007 exist

## Seed catalogue (after V1 + V2 + V3)

| SKU | Name | Stock | Use |
| --- | --- | --- | --- |
| SKU-001 | Notebook | 100 | Happy-path workhorse |
| SKU-002 | Mechanical Keyboard | 20 | Generic |
| SKU-003 | Wireless Mouse | 50 | Generic |
| SKU-004 | Monitor 27" | 5 | Cheap way to push order total past 5000 fail-threshold |
| SKU-005 | Flash Sale GPU | 10 | **Load test** — small stock makes contention obvious |
| SKU-006 | Coffee Mug (bulk) | 10000 | High-volume happy path |
| SKU-007 | Limited Hoodie | 200 | Medium-volume race tests |

## Quick recipes

```powershell
# Smoke test everything
pwsh test-data/run-scenarios.ps1

# Load test under REDIS (default) — 100 concurrent buyers for SKU-005 (10 units)
pwsh test-data/load-test.ps1

# Same load test under DATABASE engine (watch the wall-clock difference)
pwsh test-data/load-test.ps1 -Mode DATABASE

# Heavy contention — 500 buyers fighting over SKU-005
pwsh test-data/load-test.ps1 -N 500 -Each 1
```

## What load-test.ps1 verifies

- **No oversell**: stock taken == confirmed orders × quantity. If REDIS or
  DATABASE engine ever lets two orders both decrement the last unit, this
  check catches it.
- **Wall-clock comparison**: pessimistic-lock serializes contended requests;
  Redis Lua doesn't. The DATABASE run should be visibly slower than REDIS for
  high contention.
- **Compensating-action symmetry**: cancelled orders restore stock, so the
  conservation check still holds across both code paths.
