-- Contract v1.0: append-only domain facts, world snapshot, schedules, visible outbox and idempotency.
CREATE TABLE worlds (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    canon_version_id TEXT NOT NULL,
    rule_version TEXT NOT NULL,
    world_seed BIGINT NOT NULL CHECK (world_seed >= 0),
    world_time BIGINT NOT NULL DEFAULT 0 CHECK (world_time >= 0),
    location_id TEXT NOT NULL DEFAULT 'square',
    clock_mode TEXT NOT NULL DEFAULT 'interaction'
        CHECK (clock_mode IN ('interaction','exploration','fast_forward')),
    view_revision TEXT NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_seq >= 0)
);
CREATE INDEX worlds_owner_idx ON worlds(owner_id);

CREATE TABLE world_events (
    world_id TEXT NOT NULL REFERENCES worlds(id),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_id TEXT NOT NULL UNIQUE,
    occurred_at_world BIGINT NOT NULL CHECK (occurred_at_world >= 0),
    recorded_at_utc TIMESTAMPTZ NOT NULL,
    type TEXT NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    actor_id TEXT,
    target_ids_json JSONB NOT NULL,
    payload_json JSONB NOT NULL,
    caused_by_json JSONB NOT NULL,
    command_id TEXT,
    rule_version TEXT NOT NULL,
    PRIMARY KEY(world_id, sequence)
);

CREATE FUNCTION prohibit_world_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'World events are append-only';
END
$$;
CREATE TRIGGER prohibit_world_event_update
    BEFORE UPDATE OR DELETE ON world_events FOR EACH ROW
    EXECUTE FUNCTION prohibit_world_event_mutation();

CREATE TABLE command_results (
    world_id TEXT NOT NULL REFERENCES worlds(id),
    actor_id TEXT NOT NULL,
    command_id TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    response_json JSONB NOT NULL,
    PRIMARY KEY(world_id, actor_id, command_id)
);

CREATE TABLE visible_events (
    ordinal BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    world_id TEXT NOT NULL REFERENCES worlds(id),
    viewer_id TEXT NOT NULL,
    event_id TEXT NOT NULL UNIQUE,
    cursor TEXT NOT NULL UNIQUE,
    world_time BIGINT NOT NULL CHECK (world_time >= 0),
    kind TEXT NOT NULL,
    payload_json JSONB NOT NULL
);
CREATE INDEX visible_events_viewer_idx ON visible_events(world_id,viewer_id,ordinal);

CREATE TABLE scheduled_events (
    id TEXT PRIMARY KEY,
    world_id TEXT NOT NULL REFERENCES worlds(id),
    due_at_world BIGINT NOT NULL CHECK (due_at_world >= 0),
    priority INTEGER NOT NULL,
    insertion_seq BIGINT NOT NULL CHECK (insertion_seq >= 0),
    kind TEXT NOT NULL,
    executed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX scheduled_events_due_idx ON scheduled_events(
    world_id, executed, due_at_world, priority, insertion_seq, id
);

CREATE TABLE actor_beliefs (
    world_id TEXT NOT NULL REFERENCES worlds(id),
    actor_id TEXT NOT NULL,
    fact_key TEXT NOT NULL,
    fact_value TEXT NOT NULL,
    PRIMARY KEY(world_id, actor_id, fact_key)
);
