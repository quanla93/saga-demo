// Saga Demo — k6 ramp + spike stress test
//
// Designed to surface:
//   1) Throughput ceiling of order-service POST /api/orders
//   2) Latency degradation curve (p95, p99) as concurrency climbs
//   3) Breaking point — where error rate exceeds threshold
//   4) Recovery behaviour after the spike
//
// Compare runs against REDIS vs DATABASE stock engine — flip with:
//   curl -X POST http://localhost:8083/admin/stock-engine/REDIS
//   curl -X POST http://localhost:8083/admin/stock-engine/DATABASE
//
// Run:           k6 run test-data/k6-stress.js
//   With JSON output for later analysis:
//                k6 run --out json=results.json test-data/k6-stress.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const ORDER_URL = __ENV.ORDER_BASE_URL || __ENV.ORDER_URL || 'http://localhost:8081';

// SKU-006 (mug) has 10k stock — safe to hammer without exhausting inventory.
// Switch to SKU-005 GPU (10 stock) if you want to test contention/oversell behaviour.
const PRODUCT_ID = __ENV.PRODUCT_ID || '66666666-6666-6666-6666-666666666666';

// Custom metrics so the summary makes business sense, not just HTTP sense.
const ordersAccepted = new Counter('orders_accepted');
const businessErrors = new Rate('business_errors');

export const options = {
  scenarios: {
    rampUp: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '30s',  target: 50  },   // warm-up
        { duration: '1m',   target: 200 },   // moderate
        { duration: '1m',   target: 500 },   // expected peak
        { duration: '30s',  target: 1000 },  // spike
        { duration: '30s',  target: 1000 },  // sustain spike
        { duration: '30s',  target: 0   },   // cool-down (observe recovery)
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // Pass/fail criteria for the run.
    http_req_duration:     ['p(95)<1000', 'p(99)<3000'],
    http_req_failed:       ['rate<0.05'],   // <5% HTTP errors during stress is acceptable
    business_errors:       ['rate<0.02'],   // <2% non-201 responses
    orders_accepted:       ['count>5000'],  // sanity: real work happened
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const payload = JSON.stringify({
    customerId: `00000000-0000-0000-0000-${String(__VU).padStart(12, '0')}`,
    items: [{ productId: PRODUCT_ID, quantity: 1, unitPrice: 5.00 }],
  });
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': `stress-${__VU}-${__ITER}`,
  };

  const res = http.post(`${ORDER_URL}/api/orders`, payload, { headers });

  const ok = check(res, {
    '201 Created':         (r) => r.status === 201,
    'orderId present':     (r) => r.status === 201 && r.json('orderId') !== null,
  });

  if (res.status === 201) ordersAccepted.add(1);
  businessErrors.add(!ok);
}

// Print custom post-run summary so the result is readable without scrolling.
export function handleSummary(data) {
  const m = data.metrics;
  const pull = (name, field = 'rate') =>
    (m[name] && m[name].values && m[name].values[field] !== undefined)
      ? m[name].values[field] : 'n/a';

  const report =
    '\n========== Saga Demo Stress Test ==========\n' +
    `Orders accepted (201)      : ${pull('orders_accepted', 'count')}\n` +
    `Business error rate        : ${(pull('business_errors') * 100).toFixed(2)}%\n` +
    `HTTP error rate            : ${(pull('http_req_failed') * 100).toFixed(2)}%\n` +
    `Throughput (req/s avg)     : ${pull('http_reqs', 'rate').toFixed(1)}\n` +
    `Latency p50 / p95 / p99 ms : ` +
      `${pull('http_req_duration', 'med').toFixed(0)} / ` +
      `${pull('http_req_duration', 'p(95)').toFixed(0)} / ` +
      `${pull('http_req_duration', 'p(99)').toFixed(0)}\n` +
    '===========================================\n';

  const summaryPath = __ENV.K6_SUMMARY_PATH || 'k6-summary.json';
  return {
    'stdout': report,
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
