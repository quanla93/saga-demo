# Saga Demo Benchmark Report

Generated at: {{generatedAt}}

## Scenario

- k6 script: `{{k6Script}}`
- Compared stock engines: {{engines}}
- Services expected:
  - order-service: `{{orderServiceUrl}}`
  - inventory-service: `{{inventoryServiceUrl}}`

## Results

| Engine | k6 summary | Exit code | Notes |
| --- | --- | ---: | --- |
{{rows}}

## How to interpret

- Compare `http_req_duration` p95/p99 for tail latency.
- Compare `http_reqs` rate for throughput.
- Compare custom `business_errors` for expected business rejections.
- Check service logs and Prometheus/Grafana for outbox lag, Kafka activity, and HTTP error spikes.

## Interview talking points

- `DATABASE` mode is correctness-first and relies on row-level locking.
- `REDIS` mode moves hot-stock decrement to an atomic Lua path.
- The invariant is not only fast responses; final stock must remain consistent and no oversell should occur.
