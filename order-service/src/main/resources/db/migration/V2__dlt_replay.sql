CREATE TABLE dlt_messages (
    id                  UUID PRIMARY KEY,
    message_id          UUID,
    original_topic      VARCHAR(128) NOT NULL,
    dlt_topic           VARCHAR(128) NOT NULL,
    message_key         VARCHAR(256),
    payload             TEXT NOT NULL,
    headers_json        TEXT,
    exception_class     TEXT,
    exception_message   TEXT,
    status              VARCHAR(32) NOT NULL,
    replay_attempts     INT NOT NULL DEFAULT 0,
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_action_at      TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_dlt_message_source ON dlt_messages(dlt_topic, message_key, payload);
CREATE INDEX idx_dlt_messages_status ON dlt_messages(status);

CREATE TABLE dlt_audit_logs (
    id                  UUID PRIMARY KEY,
    dlt_message_id      UUID NOT NULL REFERENCES dlt_messages(id) ON DELETE CASCADE,
    action              VARCHAR(32) NOT NULL,
    operator_name       VARCHAR(128) NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dlt_audit_message ON dlt_audit_logs(dlt_message_id, created_at);
