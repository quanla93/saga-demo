-- Outbox publisher retry + parking-lot fields. See order-service V3 for rationale.
ALTER TABLE outbox_events ADD COLUMN attempts   INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN last_error TEXT;
ALTER TABLE outbox_events ADD COLUMN parked_at  TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox_events(occurred_at)
    WHERE published_at IS NULL AND parked_at IS NULL;

CREATE INDEX idx_outbox_parked ON outbox_events(parked_at)
    WHERE parked_at IS NOT NULL;
