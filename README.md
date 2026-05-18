# Saga Demo — Orchestration with Outbox / Inbox / Idempotency

A small but production-flavoured demo of the **Saga pattern (orchestration variant)**
across three Spring Boot services backed by Kafka and Postgres. Built to show:

- Atomic state change + reliable event publication via the **Transactional Outbox** pattern
- Consumer-side dedup via the **Inbox** pattern
- HTTP-level idempotency via an `Idempotency-Key` header
- A central **orchestrator** that drives the saga state machine and triggers compensating actions on failure

A separate `ui-service` (BFF) renders a dashboard via Thymeleaf + HTMX, calling the
`order-service` and `inventory-service` REST APIs over HTTP — frontend is fully
decoupled from the saga participants.

---

## Architecture

```
                        +---------------------+
                        |     ui-service      |   :8080
                        |  Thymeleaf + HTMX   |
                        +----------+----------+
                         REST       |       REST
                         (orders)   |       (products)
                                    v
   +-----------------+        +----+----------+        +--------------------+
   |  Browser / curl | -----> | order-service |        | inventory-service  |
   +-----------------+  REST  |   :8081       |        |   :8083            |
                              |  ORCHESTRATOR |        |  PARTICIPANT       |
                              +---+-------+---+        +----+----------+----+
                                  | outbox |                | outbox  | inbox
                                  v        ^                v         ^
                          +-------+--------+----------------+---------+------+
                          |                       Kafka                       |
                          |  inventory.commands  inventory.events             |
                          |  payment.commands    payment.events               |
                          +-----+----------+----------------------------------+
                                |          ^
                          inbox |          | outbox
                                v          |
                              +------------+--------+
                              |   payment-service   |   :8082
                              |   PARTICIPANT       |
                              +---------------------+

   Postgres (single instance, 3 schemas):
     order_db.{orders, saga_instances, outbox_events, inbox_messages, idempotency_keys, order_items}
     payment_db.{payments, outbox_events, inbox_messages}
     inventory_db.{products, reservations, reservation_items, outbox_events, inbox_messages}
```

### Happy path

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Order as order-service<br/>(orchestrator)
    participant Kafka
    participant Inv as inventory-service
    participant Pay as payment-service

    Client->>Order: POST /api/orders<br/>(Idempotency-Key)
    Note over Order: TX: INSERT order + saga(STARTED)<br/>+ outbox(ReserveInventory)
    Order-->>Client: 201 Created (saga=STARTED)

    Order->>Kafka: ReserveInventory<br/>(via OutboxPublisher)
    Kafka->>Inv: deliver
    Note over Inv: TX: lock products + decr stock<br/>+ insert reservation<br/>+ outbox(InventoryReserved)<br/>+ inbox(messageId)
    Inv->>Kafka: InventoryReserved
    Kafka->>Order: deliver

    Note over Order: TX: saga → INVENTORY_RESERVED<br/>+ outbox(ChargePayment)<br/>+ inbox(messageId)
    Order->>Kafka: ChargePayment
    Kafka->>Pay: deliver

    Note over Pay: TX: insert payment(COMPLETED)<br/>+ outbox(PaymentCompleted)<br/>+ inbox(messageId)
    Pay->>Kafka: PaymentCompleted
    Kafka->>Order: deliver

    Note over Order: TX: saga → COMPLETED<br/>order → CONFIRMED<br/>+ inbox(messageId)
```

### Compensating path (payment fails)

```mermaid
sequenceDiagram
    autonumber
    participant Order as order-service
    participant Kafka
    participant Inv as inventory-service
    participant Pay as payment-service

    Note over Pay: amount > fail-above-amount
    Pay->>Kafka: PaymentFailed
    Kafka->>Order: deliver
    Note over Order: TX: saga → COMPENSATING_RELEASE_INVENTORY<br/>+ outbox(ReleaseInventory)
    Order->>Kafka: ReleaseInventory
    Kafka->>Inv: deliver
    Note over Inv: TX: lock products + restore stock<br/>+ reservation → RELEASED<br/>+ outbox(InventoryReleased)
    Inv->>Kafka: InventoryReleased
    Kafka->>Order: deliver
    Note over Order: TX: saga → FAILED<br/>order → CANCELLED (with reason)
```

---

## Why orchestration (and not choreography)?

This was a deliberate choice for the demo's business case (time-windowed sales,
strict ordering of steps, central observability requirement).

|                                | **Orchestration** (this repo)                                                                 | **Choreography**                                                                                       |
| ------------------------------ | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| Where the state machine lives  | Central orchestrator (`SagaOrchestrator` in order-service)                                    | Spread across services — each service reacts to events and emits the next event                        |
| Adding a new step              | Edit one class                                                                                | Edit several services + their event contracts                                                          |
| Observability                  | Easy — one `saga_instances` row tells you the current step                                    | Hard — you have to trace events across services to reconstruct state                                   |
| Risk of cycles / lost events   | Low — orchestrator decides what comes next                                                    | Higher — accidental event loops are easy to introduce                                                  |
| Coupling                       | Participants are dumb (only know their own job)                                               | Participants must know which downstream event to emit                                                  |
| Single point of failure        | Orchestrator. Mitigation: store state in DB, restart-safe                                     | None — but harder to debug because no one "owns" the flow                                              |
| Fits this domain?              | **Yes**. Orders inside time-windowed sales need centralised control of step order             | Worse fit — decentralised flow makes flash-sale cut-offs harder to enforce                              |

In interviews, the wrong answer is "X is always better." The right answer is:
*orchestration when the business needs centralised control / observability;
choreography when participants are truly independent and the workflow rarely changes.*

---

## Three patterns, three problems they solve

### 1. Transactional Outbox

**Problem.** A service needs to atomically (a) update its DB and (b) publish a
Kafka message. There's no distributed transaction across Postgres + Kafka, so a
naive `repository.save(); kafka.send();` can leave the system in either of two
broken states:

- DB commit succeeds, Kafka publish fails → downstream never knows about the change
- DB commit fails, Kafka publish succeeded → downstream acts on a change that didn't happen

**Solution.** In the same DB transaction as the business change, insert a row
into a local `outbox_events` table. A scheduled `OutboxPublisher` polls
unpublished rows and pushes them to Kafka, marking them sent. Either both
commit together, or neither — and any temporary Kafka outage just delays
delivery instead of losing the event.

See: `OutboxRecorder.java`, `OutboxPublisher.java` in each service.

### 2. Inbox (consumer-side dedup)

**Problem.** Kafka guarantees at-least-once delivery. A consumer may see the
same `messageId` twice (rebalance, redelivery after crash, outbox republish on
retry). Applying business effects twice is unacceptable for charges /
reservations.

**Solution.** Each consumer has an `inbox_messages` table keyed on `messageId`.
Inside the same transaction as the business change, we insert into inbox. On a
duplicate, the PK conflict tells us to skip. Result: **exactly-once effective**
processing without distributed transactions.

See: `InboxGuard.java` in each service.

### 3. HTTP idempotency key

**Problem.** A client retries `POST /api/orders` because the network blipped.
Without protection, we'd create two identical orders and run two sagas.

**Solution.** Client sends `Idempotency-Key: <uuid>` header. order-service
checks `idempotency_keys` table; on hit, returns the existing order id without
side effects.

See: `OrderService.createOrder` in `order-service`.

### 4. Consumer retry + Dead-Letter Topics

**Problem.** Transient failures happen all the time: the DB is briefly
unavailable, a downstream is slow, a Kafka rebalance hits mid-handler. Without
a retry policy, the listener re-throws and Kafka redelivers forever — pinning
the partition and blocking every subsequent message.

**Solution.** Each service registers a `DefaultErrorHandler` wired to a
`DeadLetterPublishingRecoverer`:

- **5 total attempts** per record (1 original + 4 retries) with a **1-second
  back-off** between attempts
- On the 5th failure, the record is published to `<topic>.DLT` (Spring's
  default Dead Letter Topic naming) and the offset is committed so the
  partition moves on
- `IllegalArgumentException`, `IllegalStateException`, and `JsonProcessingException`
  are **not retried** — they're programming or data errors that won't fix
  themselves, so we fail fast straight to DLT

**Interaction with the Inbox.** Because the inbox-row insert and the business
effect commit together inside the listener's `@Transactional` boundary, a
failed attempt rolls back BOTH — leaving the inbox empty for that messageId.
The next retry therefore proceeds as a genuine first attempt, not a duplicate.

**DLT topics created automatically** (Kafka auto-create is on):
- `inventory.commands.DLT`, `inventory.events.DLT`
- `payment.commands.DLT`, `payment.events.DLT`

**Operating the DLT.** Inspect failed records in Kafdrop
(<http://localhost:9000>) — Spring adds headers like
`kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, and
`kafka_dlt-original-offset` to every DLT record so you can triage without
reaching for logs. A real deployment pairs this with an alert ("DLT > 0
records in last 5 min") and a small replay tool.

See: `KafkaErrorHandlerConfig.java` in each of order-service / payment-service /
inventory-service.

---

## Hot-path stock: Redis engine + runtime router

### Why Redis at all

A pessimistic-lock `SELECT ... FOR UPDATE` on a row in `products` is correct
but serializes every reservation that touches the same SKU. For ~100 req/s
that's fine — for flash-sale traffic (10k+ req/s on a single hot SKU) the
lock queue explodes, deadlock-detector fires constantly, and DB CPU pegs.

Big e-commerce platforms (Shopee, Lazada, Tokopedia, Amazon) keep "available
stock" in an in-memory store **24/7**, not just during sale windows. Postgres
remains the authoritative log for orders + audit + product catalog; Redis is
the source of truth for the "is there enough stock right now" question the
order flow asks thousands of times per second.

### Two engines, one interface

This demo ships **both implementations behind the same
`StockReservationEngine` interface** so you can compare them side by side:

| Engine | Source of truth for available stock | Concurrency primitive | Throughput on hot SKU |
| --- | --- | --- | --- |
| `DatabaseStockEngine` | Postgres `products.stock_available` | Row-level pessimistic lock, ordered by id to avoid deadlocks | Tens of req/s per SKU |
| `RedisStockEngine` | Redis `stock:{productId}` integer keys | Atomic Lua script (single-threaded server-side) | Thousands of req/s per SKU |

The Redis engine's Lua script does check + decrement + idempotency-set update
in **one round-trip, atomically**. It also keys an idempotency `SET` by
`orderId`, so a retried `ReserveInventory` command from Kafka decrements stock
only once even if the listener handler re-runs.

### Runtime engine switching (no restart)

Spring wires both engines as beans plus a `StockEngineRouter` (marked
`@Primary`) that holds a volatile mode and delegates each call to one of the
two. Mode can change through any of three paths:

| Trigger | Use case |
| --- | --- |
| Boot config `saga.inventory.stock-source` (`DATABASE` / `REDIS` / `AUTO`) | Default behaviour |
| Admin REST: `POST /admin/stock-engine/{DATABASE\|REDIS}` | Operator override — instant rollback if Redis misbehaves |
| `AUTO` + `SaleWindowSwitcher` (scheduled every 30s) | Reads configured sale windows (e.g. `10:00-10:30,15:00-16:00`) and flips DB <-> Redis automatically; pre-warms Redis on entry and reconciles Redis -> Postgres on exit |

Inspect / change at runtime:

```bash
curl http://localhost:8083/admin/stock-engine                    # {"effectiveMode":"REDIS"}
curl -X POST http://localhost:8083/admin/stock-engine/DATABASE   # reconciles Redis->DB, then flips
curl -X POST http://localhost:8083/admin/stock-engine/REDIS      # warms Redis from DB, then flips
```

### When would you actually pick AUTO over always-on Redis?

In production e-commerce: **almost never** — Redis-always-on with Redis
Cluster + Debezium CDC is the standard pattern. AUTO mode fits internal /
B2B systems that sit at near-zero load most of the time and occasionally
spike during scheduled batch jobs, where the operational cost of running
Redis 24/7 outweighs the latency win.

The router is in the demo to make the trade-off explicit and to give you an
interview talking point about reconciliation strategy. If you were forking
this for production you'd most likely delete `SaleWindowSwitcher` and pin the
mode to `REDIS`.

See: `stock/StockReservationEngine.java`, `stock/RedisStockEngine.java`,
`stock/DatabaseStockEngine.java`, `stock/StockEngineRouter.java`,
`stock/SaleWindowSwitcher.java`, `config/RedisConfig.java`.

---

## Stuck-saga detector

Retry + DLT handles transient failures but leaves a class of sagas that no
amount of Kafka redelivery will rescue: a participant crashed, the saga's
trigger event landed in the DLT and was never replayed, or an operator
intervened mid-flow. Those sagas sit in a non-terminal state with no further
input — order stuck on the dashboard at "Charging payment..." indefinitely.

`SagaTimeoutScanner` runs in order-service every `saga.timeout-scan-seconds`
(default 30s) and forces any non-terminal saga older than `saga.timeout-seconds`
(default 120s) to a safe terminal state:

| Stuck state | Recovery action |
| --- | --- |
| `STARTED` | Nothing reserved upstream -> mark order CANCELLED + saga FAILED |
| `INVENTORY_RESERVED` | Stock was decremented -> emit `ReleaseInventory` (compensating), transition to `COMPENSATING_RELEASE_INVENTORY` and let the normal flow finish it |
| `COMPENSATING_*` | Compensation itself stalled -> mark FAILED + log loudly for operator review |

Why this is needed even with retry+DLT: retry only covers the case where the
listener throws. If a message simply never arrives (lost, never produced,
crashed before publish), the listener has nothing to retry. The timeout
scanner is the saga-level safety net.

See: `saga/SagaTimeoutScanner.java`.

---

## State machine (orchestrator)

```mermaid
stateDiagram-v2
    [*] --> STARTED: POST /api/orders
    STARTED --> INVENTORY_RESERVED: InventoryReserved
    STARTED --> FAILED: InventoryReservationFailed
    INVENTORY_RESERVED --> COMPLETED: PaymentCompleted
    INVENTORY_RESERVED --> COMPENSATING_RELEASE_INVENTORY: PaymentFailed
    COMPENSATING_RELEASE_INVENTORY --> FAILED: InventoryReleased
    COMPLETED --> [*]
    FAILED --> [*]
```

Defined in `SagaState.java` and enforced by `SagaOrchestrator.java`.

---

## Running locally

### Prerequisites

- JDK 21
- Maven 3.9+
- Docker + Docker Compose

### 1. Infra

```bash
docker compose up -d
```

This starts Postgres (with the three schemas pre-created), Kafka, Zookeeper,
and Kafdrop (Kafka UI at <http://localhost:9000>).

### 2. Build all modules

```bash
mvn clean install
```

### 3. Start the services (each in its own terminal)

```bash
mvn -pl order-service spring-boot:run
mvn -pl payment-service spring-boot:run
mvn -pl inventory-service spring-boot:run
mvn -pl ui-service spring-boot:run
```

Flyway runs on first boot of each service and creates its tables in its
dedicated schema.

### 4. Open the dashboard

<http://localhost:8080>

- Pick a product, set quantity + unit price, click **Place order**.
- You'll be redirected to the order detail page; the status panel auto-refreshes
  via HTMX every second.
- Set **unit price × quantity > 5000** to force payment failure and watch the
  compensating release run end-to-end.

### REST quickref

```bash
# Create order (happy path)
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "customerId": "00000000-0000-0000-0000-000000000001",
    "items": [
      {"productId": "11111111-1111-1111-1111-111111111111", "quantity": 2, "unitPrice": 100.00}
    ]
  }'

# Inspect order + saga state
curl http://localhost:8081/api/orders/<orderId> | jq

# List products (with reserved counts)
curl http://localhost:8083/api/products | jq
```

OpenAPI / Swagger UI for order-service: <http://localhost:8081/swagger-ui.html>

### Test data + scenario scripts

The [`test-data/`](test-data/) folder ships three ready-to-run artifacts:

- **`requests.http`** — full scenario list as separate REST Client requests
  (happy path, idempotency replay, compensating, oversell, engine flip). Open
  in IntelliJ / VS Code and click each one.
- **`run-scenarios.ps1`** — end-to-end smoke test with assertions. Prints
  PASS/FAIL per scenario; exits non-zero on first failure so it works as a CI
  gate.
- **`load-test.ps1`** — fires N concurrent buyers at one SKU. Run once in
  REDIS mode and once in DATABASE mode to *see* the contention difference;
  the script also verifies stock conservation (no oversell, no leaked stock).

See [`test-data/README.md`](test-data/README.md) for the seed catalogue and
quick recipes.

---

## Code map

```
saga-demo/
├── docker-compose.yml           Postgres + Kafka + Zookeeper + Kafdrop
├── docker/postgres/init.sql     creates order_db / payment_db / inventory_db schemas
├── common/                      shared event payloads + envelope + topic constants
│   └── src/main/java/com/quanla/sagademo/common/
│       ├── Topics.java
│       └── event/
│           ├── EventEnvelope.java
│           ├── EventTypes.java
│           └── payload/         records for each command / event
├── order-service/               orchestrator + REST + idempotency
│   └── src/main/java/com/quanla/sagademo/order/
│       ├── api/                 REST controllers + DTOs
│       ├── domain/              Order, OrderItem, SagaInstance, IdempotencyKey
│       ├── saga/                OrderService, SagaOrchestrator, SagaEventListener
│       ├── outbox/              entity + repo + recorder + scheduled publisher
│       └── inbox/               entity + repo + guard
├── payment-service/             charge / refund participant
├── inventory-service/           reserve / release participant (pessimistic-lock stock)
└── ui-service/                  Thymeleaf + HTMX dashboard, calls order-service via REST
```

---

## What the demo does NOT cover (intentional scope)

- No DLT replay tool. DLT records carry enough headers to manually re-publish
  to the original topic via Kafdrop / kcat, but a real deployment ships a small
  replay endpoint with a "fix-and-retry" workflow.
- No Redis Cluster / Sentinel — single Redis container. Production would shard
  Redis by SKU hash and run with AOF + replication for durability.
- No Debezium / CDC outbox publishing. Polling is used for clarity; CDC is the
  production-grade alternative when outbox traffic gets heavy.
- No authentication / multi-tenancy.
- Single Postgres instance with three schemas (not three separate clusters).
  This still gives each service its own DDL/data, but in production each service
  would own its own database cluster.
