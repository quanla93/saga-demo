// Saga Demo — k6 smoke test
//
// Lightweight verification that the system responds correctly under low load.
// Use as a CI gate: must complete in <1 minute with 0 errors.
//
// Install k6:    winget install k6                   (Windows)
//                brew install k6                     (macOS)
//                https://k6.io/docs/get-started/installation
//
// Run:           k6 run test-data/k6-smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8081';

const PRODUCTS = {
  notebook: '11111111-1111-1111-1111-111111111111',
  mug:      '66666666-6666-6666-6666-666666666666',
};

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    // Smoke acceptance criteria — should be trivially satisfied on a working stack.
    http_req_duration: ['p(95)<500'],   // 95% of requests under 500ms
    http_req_failed:   ['rate<0.01'],    // <1% HTTP errors
    checks:            ['rate>0.99'],    // >99% of business checks pass
  },
};

export default function () {
  const payload = JSON.stringify({
    customerId: '00000000-0000-0000-0000-000000000001',
    items: [{ productId: PRODUCTS.mug, quantity: 1, unitPrice: 5.00 }],
  });
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': `smoke-${__VU}-${__ITER}-${Date.now()}`,
  };

  const res = http.post(`${ORDER_URL}/api/orders`, payload, { headers });

  check(res, {
    'POST returned 201':        (r) => r.status === 201,
    'response has orderId':     (r) => r.json('orderId') !== null,
    'initial saga is STARTED':  (r) => r.json('sagaState') === 'STARTED',
  });

  sleep(0.5);
}
