# Tech Spec: Interview-Grade Saga Demo Upgrades

## 1. Context

This project is a production-flavoured ecommerce saga orchestration demo built with Spring Boot, Kafka, Postgres, Redis, Thymeleaf/HTMX, and k6/PowerShell scenario scripts.

The current system already demonstrates:

- Saga orchestration from `order-service`.
- Inventory and payment participants.
- Transactional outbox.
- Inbox/idempotent consumer pattern.
- HTTP idempotency for order creation.
- Kafka retry and DLT.
- Stuck saga timeout scanning.
- Redis Lua-based hot-stock reservation.
- Smoke/load/stress scripts.

This tech spec defines five larger upgrades intended to make the project stronger for backend, microservices, distributed systems, and platform interviews.

## 2. Goals

Implement the following five interview-grade capabilities:

1. Testcontainers integration test suite.
2. Observability and distributed tracing.
3. DLT replay/operator console.
4. Redis vs Database stock-engine benchmark reporting.
5. Event schema/versioning and contract compatibility.

## 3. Non-goals

The first implementation pass will not attempt to fully productionize every infrastructure component.

Out of scope for the first pass:

- Real payment gateway integration.
- Production Kubernetes deployment.
- Multi-region Kafka/Postgres/Redis setup.
- Full authentication/authorization across all user-facing endpoints.
- Replacing every internal DTO at once.
- Migrating from local Docker Compose to managed cloud services.

## 4. Current architecture summary

### 4.1 Services

- `common`: shared event contracts, topics, event envelope, command/event payloads.
- `order-service`: REST API, idempotent order creation, saga orchestration, outbox publisher, inbox guard, timeout scanner.
- `inventory-service`: product APIs, stock reservation/release, database stock engine, Redis stock engine, stock engine router, outbox/inbox.
- `payment-service`: payment command handling, simulated charge/refund, outbox/inbox.
- `ui-service`: dashboard/BFF using Thymeleaf and HTMX.

### 4.2 Infrastructure

- Postgres for persistence.
- Kafka for asynchronous command/event messaging.
- Redis for hot stock reservation.
- Kafdrop for local Kafka inspection.
- k6 and PowerShell scripts for scenario/load testing.

## 5. Upgrade 1: Testcontainers integration tests

### 5.1 Objective

Add automated integration tests that run against realistic infrastructure: Postgres, Kafka, and Redis.

The goal is to prove correctness of distributed workflow behaviour, not just unit-level business logic.

### 5.2 Why this matters for interviews

This demonstrates that the project handles real distributed-system failure modes:

- At-least-once delivery.
- Duplicate messages.
- Transaction boundaries.
- Compensation.
- Idempotency.
- Stock consistency under contention.

### 5.3 Scope

Add tests for:

1. Happy path order completion.
2. Inventory reservation failure.
3. Payment failure with inventory release compensation.
4. HTTP idempotency using repeated `Idempotency-Key`.
5. Duplicate Kafka event handling through inbox deduplication.
6. Duplicate command handling without double-decrementing stock.
7. Stuck saga timeout handling.
8. Redis stock engine no-oversell invariant under concurrent reservations.

### 5.4 Proposed dependencies

Add test dependencies to relevant service modules:

- `org.testcontainers:junit-jupiter`
- `org.testcontainers:postgresql`
- `org.testcontainers:kafka`
- `org.testcontainers:testcontainers`
- Spring Boot test support already exists where available.

Redis can be handled either by:

- Testcontainers `GenericContainer` using `redis:7-alpine`, or
- Docker Compose based test setup if preferred later.

### 5.5 Proposed test structure

Potential files:

- `order-service/src/test/java/.../OrderSagaIntegrationTest.java`
- `order-service/src/test/java/.../OrderIdempotencyIntegrationTest.java`
- `order-service/src/test/java/.../SagaTimeoutScannerIntegrationTest.java`
- `inventory-service/src/test/java/.../DatabaseStockEngineIntegrationTest.java`
- `inventory-service/src/test/java/.../RedisStockEngineIntegrationTest.java`
- `payment-service/src/test/java/.../PaymentCommandIntegrationTest.java`

If cross-service orchestration tests become too heavy inside a single module, create a dedicated Maven module later:

- `system-tests`

First pass should avoid introducing the extra module unless needed.

### 5.6 Acceptance criteria

- `mvn test` runs integration tests reliably on a developer machine with Docker available.
- Tests start isolated Postgres/Kafka/Redis containers.
- Tests do not depend on manually running `docker-compose.yml`.
- Tests verify both final state and important invariants.
- Duplicate messages do not create duplicate business effects.
- Payment failure leads to inventory release.
- Concurrent Redis reservations never oversell.

### 5.7 Risks

- Cross-service integration tests may be slow if all services are booted together.
- Kafka tests can be flaky if topic creation and consumer group startup are not controlled.
- The existing use of separate service databases/schemas may require careful dynamic property setup.

### 5.8 Mitigation

- Start with focused service-level integration tests.
- Use Awaitility for eventual assertions.
- Use deterministic topic names or isolated consumer groups per test class.
- Keep full end-to-end multi-service tests limited to high-value scenarios.

## 6. Upgrade 2: Observability and distributed tracing

### 6.1 Objective

Make every order and saga traceable across HTTP, Kafka, service logs, metrics, and dashboards.

### 6.2 Why this matters for interviews

This shows production thinking. Distributed systems are hard to operate unless failures can be observed and correlated.

### 6.3 Scope

Add:

1. Micrometer metrics.
2. Prometheus scraping support.
3. Grafana dashboard provisioning.
4. OpenTelemetry tracing.
5. Correlation ID propagation through HTTP and Kafka.
6. Structured logs containing `correlationId`, `orderId`, `sagaId`, and Kafka `messageId` where applicable.

### 6.4 Metrics to expose

#### Order service

- `saga_started_total`
- `saga_completed_total`
- `saga_failed_total`
- `saga_compensated_total`
- `saga_timeout_total`
- `saga_state_current{state=...}`
- `saga_duration_seconds`
- `outbox_pending_count`
- `outbox_publish_total`
- `inbox_duplicate_total`

#### Inventory service

- `inventory_reservation_success_total`
- `inventory_reservation_failed_total`
- `inventory_release_total`
- `inventory_stock_engine_current`
- `inventory_stock_decrement_duration_seconds`
- `inventory_redis_no_stock_total`
- `inventory_db_lock_duration_seconds`

#### Payment service

- `payment_charge_success_total`
- `payment_charge_failed_total`
- `payment_refund_total`
- `payment_duration_seconds`

#### Kafka/operational

- DLT message counts.
- Consumer lag if supported through Micrometer Kafka metrics.
- Outbox backlog.
- Inbox duplicate count.

### 6.5 Tracing design

Use OpenTelemetry-compatible tracing through Micrometer Tracing or direct OTel Java agent support.

Trace propagation:

- Incoming HTTP request receives or creates a correlation ID.
- `order-service` stores the correlation ID with saga/order context where useful.
- Kafka command/event headers carry correlation metadata.
- Consumers restore correlation metadata into logging MDC.
- UI can display correlation ID in order detail.

### 6.6 Docker Compose additions

Add local observability components:

- Prometheus.
- Grafana.
- Optional OpenTelemetry Collector.
- Optional Tempo/Jaeger for tracing visualization.

Possible files:

- `docker/prometheus/prometheus.yml`
- `docker/grafana/provisioning/datasources/datasource.yml`
- `docker/grafana/provisioning/dashboards/dashboard.yml`
- `docker/grafana/dashboards/saga-overview.json`
- `docker/otel/otel-collector.yml`

### 6.7 Acceptance criteria

- Each Spring Boot service exposes `/actuator/prometheus`.
- Prometheus scrapes all services locally.
- Grafana dashboard shows saga, inventory, payment, outbox, inbox, and DLT health.
- A single order can be followed across logs/traces from UI/API to Kafka consumers.
- Kafka message headers include correlation metadata.

### 6.8 Risks

- Adding tracing may create noisy code if done manually everywhere.
- Grafana JSON can become hard to maintain.
- Metric cardinality can explode if labels include high-cardinality IDs.

### 6.9 Mitigation

- Use low-cardinality metric labels only.
- Put high-cardinality IDs in traces/logs, not metric labels.
- Centralize correlation handling in filters/interceptors/config classes.

## 7. Upgrade 3: DLT replay/operator console

### 7.1 Objective

Turn DLT from a passive failure sink into an operational recovery workflow.

### 7.2 Why this matters for interviews

Many systems add DLT but stop there. A replay console demonstrates understanding of real production operations.

### 7.3 Scope

Add operator capabilities to inspect, quarantine, and replay failed Kafka messages.

### 7.4 Proposed capabilities

1. List DLT messages.
2. Inspect original topic, key, payload, headers, exception metadata, and timestamp.
3. Mark a DLT message as quarantined.
4. Replay a DLT message to the original topic.
5. Record audit history for every operator action.
6. Show DLT messages and replay status in UI.

### 7.5 Proposed design

Create DLT management in `order-service` first because it already owns saga orchestration and operational state.

Potential package:

- `order-service/src/main/java/com/quanla/sagademo/order/ops/dlt`

Potential classes:

- `DltMessageRecord`
- `DltMessageRepository`
- `DltMessageScanner`
- `DltReplayService`
- `DltAdminController`
- `DltAuditLog`

Potential UI additions:

- `ui-service` page for DLT records.
- Replay/quarantine buttons.
- Detail view with payload and error metadata.

### 7.6 Data model

Potential table: `dlt_messages`

Fields:

- `id`
- `message_id`
- `original_topic`
- `dlt_topic`
- `message_key`
- `payload_json`
- `headers_json`
- `exception_class`
- `exception_message`
- `status`: `NEW`, `REPLAYED`, `QUARANTINED`, `REPLAY_FAILED`
- `first_seen_at`
- `last_action_at`

Potential table: `dlt_audit_logs`

Fields:

- `id`
- `dlt_message_id`
- `action`: `REPLAY`, `QUARANTINE`, `REPLAY_FAILED`
- `operator`
- `reason`
- `created_at`

### 7.7 Replay safety rules

Replay should:

- Preserve original key where possible.
- Preserve correlation headers.
- Add replay metadata headers:
  - `x-replayed-from-dlt=true`
  - `x-dlt-record-id=<id>`
  - `x-replay-attempt=<n>`
- Avoid replaying already quarantined records unless explicitly unquarantined later.
- Avoid blind infinite replay loops.

### 7.8 Acceptance criteria

- DLT messages are persisted to an operator-readable table.
- Operator can inspect DLT records through API and UI.
- Operator can replay a message to the original topic.
- Operator can quarantine a message.
- Replay/quarantine actions are audited.
- Replayed messages retain idempotency/correlation semantics.

### 7.9 Risks

- Consuming DLT messages into a DB may change the existing Kafdrop-based workflow.
- Replaying malformed poison messages can create repeated failures.
- Replay can be dangerous without authorization.

### 7.10 Mitigation

- Keep Kafdrop available.
- Track replay attempt count.
- Add quarantine status.
- In the first local-demo version, expose APIs plainly but document that real deployments must protect them with auth.

## 8. Upgrade 4: Redis vs Database stock-engine benchmark reporting

### 8.1 Objective

Create a reproducible performance benchmark comparing database-based stock reservation and Redis Lua-based stock reservation under contention.

### 8.2 Why this matters for interviews

This gives the project a strong performance and scalability story backed by measurable evidence.

### 8.3 Scope

Build on existing files in `test-data`:

- `k6-smoke.js`
- `k6-stress.js`
- `load-test.ps1`
- `requests.http`

Add benchmark automation and reporting.

### 8.4 Proposed benchmark scenarios

1. Low-contention normal catalog orders.
2. Hot SKU flash-sale contention.
3. Payment failure compensation load.
4. Client retry/idempotency behaviour under timeout/retry.
5. Database engine vs Redis engine comparison.

### 8.5 Metrics to capture

- Throughput: orders/sec.
- HTTP latency p50/p95/p99.
- Business success rate.
- Business failure rate due to insufficient stock.
- Technical error rate.
- Final stock value.
- Reserved quantity.
- Completed orders.
- Failed/compensated orders.
- No-oversell invariant.
- Outbox backlog after test.
- DLT count after test.

### 8.6 Proposed files

- `test-data/benchmark.ps1`
- `test-data/benchmark-config.json`
- `test-data/benchmark-report-template.md`
- `test-data/results/.gitkeep`

Optional later:

- `test-data/analyze-k6-results.ps1`
- `test-data/benchmark-summary.md`

### 8.7 Benchmark flow

For each stock engine:

1. Reset or seed test data.
2. Switch stock engine via admin API.
3. Warm Redis cache when testing Redis mode.
4. Run k6 scenario.
5. Wait for async saga completion.
6. Collect final DB/API state.
7. Collect k6 result summary.
8. Assert no oversell.
9. Write result file.

### 8.8 Acceptance criteria

- One command can run benchmark for both `DATABASE` and `REDIS` modes.
- Benchmark output includes comparable metrics.
- Report clearly explains trade-offs.
- Benchmark validates final inventory consistency.
- Results are saved under `test-data/results` or equivalent ignored/generated location.

### 8.9 Risks

- Benchmarks can be noisy on local machines.
- Redis may look better/worse depending on hardware, Docker, Kafka, and Postgres tuning.
- Async saga completion means immediate HTTP response metrics may not tell the full story.

### 8.10 Mitigation

- Document that results are local and comparative, not absolute.
- Capture both request latency and eventual saga completion.
- Run each scenario multiple times if needed.
- Keep generated raw results out of Git by default.

## 9. Upgrade 5: Event schema/versioning and contract compatibility

### 9.1 Objective

Reduce lockstep coupling caused by shared Java event DTOs and demonstrate safe event evolution.

### 9.2 Why this matters for interviews

Event-driven microservices need versioned contracts. This upgrade demonstrates independent deployability and compatibility thinking.

### 9.3 Current state

The `common` module contains Java records for commands and events. This is simple and useful for a demo, but all services compile against the same Java classes.

### 9.4 Target direction

Introduce explicit event schema/versioning while keeping the first implementation manageable.

Recommended staged approach:

1. Add schema version metadata to `EventEnvelope`.
2. Add JSON Schema files for command/event payloads.
3. Add contract tests that validate serialized events against schemas.
4. Add compatibility tests for additive changes.
5. Later optionally migrate to Avro/Protobuf and Schema Registry.

This avoids a large disruptive rewrite in the first pass.

### 9.5 Proposed schema metadata

Extend event envelope with fields such as:

- `eventId`
- `eventType`
- `schemaVersion`
- `occurredAt`
- `correlationId`
- `payload`

If fields already exist partially, update only what is missing.

### 9.6 Proposed schema files

Potential directory:

- `common/src/main/resources/schemas/events`

Potential files:

- `reserve-inventory-command.v1.schema.json`
- `release-inventory-command.v1.schema.json`
- `charge-payment-command.v1.schema.json`
- `refund-payment-command.v1.schema.json`
- `inventory-reserved-event.v1.schema.json`
- `inventory-reservation-failed-event.v1.schema.json`
- `inventory-released-event.v1.schema.json`
- `payment-completed-event.v1.schema.json`
- `payment-failed-event.v1.schema.json`
- `payment-refunded-event.v1.schema.json`

### 9.7 Contract tests

Potential tests:

- Serialize each command/event and validate against JSON Schema.
- Deserialize sample v1 fixtures into current Java records.
- Verify additive fields do not break old consumers when configured to ignore unknown properties.
- Verify required field removal breaks compatibility checks.

### 9.8 Potential dependencies

Options:

- NetworkNT JSON Schema validator.
- Everit JSON Schema validator.
- Just use Jackson plus explicit fixture tests in first pass.

Recommended first pass:

- Use JSON Schema validator if dependency impact is acceptable.
- Keep schemas human-readable.

### 9.9 Acceptance criteria

- Every Kafka command/event has an explicit schema version.
- Serialized event samples validate against schema files.
- Compatibility tests demonstrate safe additive evolution.
- Consumers tolerate unknown future fields where appropriate.
- README documents event versioning strategy.

### 9.10 Risks

- Full Avro/Schema Registry migration may be too large for one pass.
- Updating all event producers/consumers can touch many files.
- Over-engineering contracts may distract from the core saga demo.

### 9.11 Mitigation

- Start with JSON Schema and version metadata.
- Keep shared Java DTOs initially.
- Treat Avro/Schema Registry as a later optional phase.

## 10. Recommended implementation phases

### Phase 1: Contract and test foundation

Implement:

1. Event envelope schema version metadata if missing.
2. JSON Schema files for existing commands/events.
3. Contract serialization tests.
4. Focused unit tests for saga transition behaviour.

Why first:

- Low operational complexity.
- Creates confidence before changing runtime behaviour.

### Phase 2: Testcontainers correctness suite

Implement:

1. Postgres container integration tests.
2. Kafka container integration tests.
3. Redis container integration tests.
4. Saga happy/failure/idempotency scenarios.
5. Stock engine no-oversell tests.

Why second:

- Proves distributed correctness.
- Prevents later observability/operator changes from regressing behaviour.

### Phase 3: Observability MVP

Implement:

1. Spring Boot actuator/prometheus endpoints.
2. Micrometer custom metrics.
3. Correlation ID propagation.
4. Prometheus and Grafana in Docker Compose.
5. Basic dashboard.

Why third:

- Makes later benchmark and DLT work visible.

### Phase 4: Benchmark automation

Implement:

1. Benchmark runner script.
2. Engine switching.
3. k6 result capture.
4. Final invariant validation.
5. Markdown summary output.

Why fourth:

- Uses metrics and correctness checks from earlier phases.

### Phase 5: DLT replay/operator console

Implement:

1. DLT record persistence.
2. DLT admin APIs.
3. Replay/quarantine service.
4. Audit log.
5. UI page.

Why fifth:

- Most operationally sensitive.
- Benefits from tracing/metrics already being present.

## 11. Suggested commit breakdown

If committing later, split into logical commits:

1. Add event schema versioning and contract tests.
2. Add Testcontainers integration test infrastructure.
3. Add saga and stock integration tests.
4. Add actuator/prometheus metrics and correlation IDs.
5. Add Prometheus/Grafana local observability stack.
6. Add benchmark runner and report generation.
7. Add DLT persistence and admin APIs.
8. Add DLT operator UI.

## 12. Interview narrative after completion

After these upgrades, the project can be presented as:

> A production-flavoured ecommerce saga orchestration system. It uses Kafka-based asynchronous messaging, transactional outbox, inbox deduplication, HTTP idempotency, compensation, timeout recovery, Redis Lua-based hot-stock reservation, automated integration testing with real infrastructure, distributed tracing, Prometheus/Grafana observability, DLT replay workflows, benchmark reporting, and event schema compatibility checks.

The strongest discussion points will be:

- Why saga orchestration was chosen over choreography.
- How outbox prevents DB/Kafka inconsistency.
- How inbox dedup handles Kafka at-least-once delivery.
- How idempotency is handled at HTTP, Kafka consumer, and Redis stock layers.
- How compensation works when payment fails.
- How stuck sagas are detected and recovered.
- Why Redis Lua helps hot SKU performance.
- How no-oversell is verified under load.
- How tracing/metrics make failures operable.
- How DLT replay is made safe and auditable.
- How event contracts evolve without breaking consumers.

## 13. Definition of done for the full upgrade set

The full upgrade set is done when:

- Automated tests cover happy path, failure path, duplicate delivery, compensation, and stock consistency.
- Services expose useful Prometheus metrics.
- A local Grafana dashboard shows saga and service health.
- Orders can be traced across service boundaries.
- DLT records can be inspected, replayed, and quarantined.
- Redis and database stock engines can be benchmarked reproducibly.
- Event schemas are versioned and validated by tests.
- README documents how to run tests, observability stack, DLT replay, and benchmarks.
