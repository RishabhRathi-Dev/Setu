CREATE TABLE IF NOT EXISTS setu_outbox (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_id uuid PRIMARY KEY,
    topic varchar(249) NOT NULL,
    partition_id integer NOT NULL CHECK (partition_id >= 0),
    payload bytea NOT NULL CHECK (octet_length(payload) <= 14680044),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz
);

CREATE INDEX IF NOT EXISTS setu_outbox_pending ON setu_outbox (sequence_id) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS setu_inbox (
    consumer_id varchar(1024) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_id, event_id)
);
