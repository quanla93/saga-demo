-- order-service schema
-- All tables live in order_db (set via Flyway default-schema).

CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    customer_id     UUID NOT NULL,
    total_amount    NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  UUID NOT NULL,
    quantity    INT NOT NULL,
    unit_price  NUMERIC(19, 2) NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);

-- Saga instance — one per order, drives the orchestrator state machine.
CREATE TABLE saga_instances (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    state           VARCHAR(48) NOT NULL,
    reservation_id  UUID,
    payment_id      UUID,
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_saga_state ON saga_instances(state);

-- Outbox: rows written in the SAME transaction as business state.
-- A scheduled poller publishes unpublished rows to Kafka, then marks them sent.
-- This avoids the dual-write problem (DB commit succeeds, Kafka publish fails or vice versa).
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    saga_id         UUID,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    UUID NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    message_key     VARCHAR(128) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         TEXT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(occurred_at) WHERE published_at IS NULL;

-- Inbox: records every messageId we've processed. Consumer checks this BEFORE
-- applying business logic so a duplicate Kafka delivery is a no-op.
CREATE TABLE inbox_messages (
    message_id      UUID PRIMARY KEY,
    source_topic    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency keys: HTTP-level dedup. Client sends Idempotency-Key header on
-- POST /api/orders; if we've seen it, we return the original order id instead
-- of creating a new one. Protects against double-submits / retries.
CREATE TABLE idempotency_keys (
    key             VARCHAR(128) PRIMARY KEY,
    order_id        UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);