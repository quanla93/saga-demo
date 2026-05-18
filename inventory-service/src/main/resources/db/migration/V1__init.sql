-- inventory-service schema. All tables in inventory_db.

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    sku             VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    stock_available INT NOT NULL,
    stock_reserved  INT NOT NULL DEFAULT 0,
    CONSTRAINT stock_non_negative CHECK (stock_available >= 0 AND stock_reserved >= 0)
);

CREATE TABLE reservations (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL UNIQUE,
    status      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservation_items (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    product_id      UUID NOT NULL,
    quantity        INT NOT NULL
);

CREATE INDEX idx_reservation_items_reservation ON reservation_items(reservation_id);

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

CREATE TABLE inbox_messages (
    message_id      UUID PRIMARY KEY,
    source_topic    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
