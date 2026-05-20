-- Outbox publisher retry + parking-lot fields.
-- attempts counts publish failures so an alert can fire when a single row
-- keeps failing. parked_at marks rows the publisher has given up on after
-- saga.outbox.max-attempts retries; the polling index excludes them so a
-- poison row no longer blocks the head-of-line for its partition forever.
ALTER TABLE outbox_events ADD COLUMN attempts   INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN last_error TEXT;
ALTER TABLE outbox_events ADD COLUMN parked_at  TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox_events(occurred_at)
    WHERE published_at IS NULL AND parked_at IS NULL;

CREATE INDEX idx_outbox_parked ON outbox_events(parked_at)
    WHERE parked_at IS NOT NULL;
