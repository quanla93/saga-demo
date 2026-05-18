// Saga Demo — homelab-friendly benchmark.
//
// Uses k6's constant-arrival-rate executor so concurrency is bounded by the
// configured rate (NOT by the request latency). On a small box where p95 can
// climb to 2s under load, ramping-vus would spiral into thousands of VUs and
// OOM-kill containers. constant-arrival-rate stays at the rate you asked for
// and adds VUs only up to maxVUs — backpressuring the workload instead of the
// system.
//
// Run:
//   k6 run -e TARGET_ORDERS=1000 -e RATE=50  test-data/k6-benchmark.js
//   k6 run -e TARGET_ORDERS=2500 -e RATE=80  test-data/k6-benchmark.js
//   k6 run -e TARGET_ORDERS=5000 -e RATE=80  test-data/k6-benchmark.js
//
// Defaults: 1000 orders at 50 req/s (~20 s wall clock).

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8081';

// SKU-006 (Coffee Mug, 10k stock). Pick something with enough stock for the
// largest run so we measure pure system throughput, not stock contention.
const PRODUCT_ID = __ENV.PRODUCT_ID || '66666666-6666-6666-6666-666666666666';

const TARGET = parseInt(__ENV.TARGET_ORDERS || '1000', 10);
const RATE   = parseInt(__ENV.RATE          || '50',   10);

// duration = ceil(TARGET / RATE) seconds, with a 5s tail so the last few
// iterations have time to land.
const DURATION_SEC = Math.ceil(TARGET / RATE);

const ordersAccepted = new Counter('orders_accepted');
const businessErrors = new Rate('business_errors');

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SEC}s`,
      // VU pool. Sized so the rate can be sustained even if p99 latency
      // climbs to a couple of seconds. Worst-case concurrency ~ rate * p99,
      // so 80 req/s × 2s p99 = 160 VUs.
      preAllocatedVUs: Math.min(50, RATE),
      maxVUs: Math.max(200, RATE * 3),
    },
  },
  thresholds: {
    // Tolerant on a 2 cpu / 2 GB stack: some load shedding is acceptable
    // under saturation, the assertion is "system stays correct + responsive".
    http_req_failed:     ['rate<0.05'],
    http_req_duration:   ['p(95)<2000', 'p(99)<5000'],
    business_errors:     ['rate<0.05'],
    orders_accepted:     [`count>${Math.floor(TARGET * 0.9)}`],
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
    'Idempotency-Key': `bench-${__VU}-${__ITER}-${Date.now()}`,
  };

  const res = http.post(`${ORDER_URL}/api/orders`, payload, { headers });

  const ok = check(res, {
    '201 Created':     (r) => r.status === 201,
    'has orderId':     (r) => r.status === 201 && r.json('orderId') !== null,
  });

  if (res.status === 201) ordersAccepted.add(1);
  businessErrors.add(!ok);
}

export function handleSummary(data) {
  const m = data.metrics;
  const pull = (name, field = 'rate') =>
    (m[name] && m[name].values && m[name].values[field] !== undefined)
      ? m[name].values[field] : 'n/a';

  const accepted = pull('orders_accepted', 'count');
  const dur      = pull('http_req_duration', 'med');
  const p95      = pull('http_req_duration', 'p(95)');
  const p99      = pull('http_req_duration', 'p(99)');
  const reqRate  = pull('http_reqs', 'rate');

  const report =
    '\n========== Saga Demo Benchmark ==========\n' +
    `Target orders             : ${TARGET}\n` +
    `Configured rate           : ${RATE} req/s for ~${DURATION_SEC}s\n` +
    `Actual throughput         : ${reqRate.toFixed ? reqRate.toFixed(1) : reqRate} req/s\n` +
    `Orders accepted (201)     : ${accepted}\n` +
    `Business error rate       : ${(pull('business_errors') * 100).toFixed(2)}%\n` +
    `HTTP error rate           : ${(pull('http_req_failed') * 100).toFixed(2)}%\n` +
    `Latency p50 / p95 / p99   : ` +
      `${dur.toFixed ? dur.toFixed(0) : dur} / ` +
      `${p95.toFixed ? p95.toFixed(0) : p95} / ` +
      `${p99.toFixed ? p99.toFixed(0) : p99} ms\n` +
    '=========================================\n';

  return {
    'stdout': report,
    'k6-benchmark-summary.json': JSON.stringify(data, null, 2),
  };
}
