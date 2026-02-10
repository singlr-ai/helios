CREATE TABLE IF NOT EXISTS helios_prompts (
    id          UUID            PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    content     TEXT            NOT NULL,
    version     INT             NOT NULL,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    variables   TEXT[]          NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL,
    UNIQUE (name, version)
);

CREATE INDEX IF NOT EXISTS idx_helios_prompts_name
    ON helios_prompts (name);

CREATE TABLE IF NOT EXISTS helios_traces (
    id          UUID            PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    start_time  TIMESTAMPTZ     NOT NULL,
    end_time    TIMESTAMPTZ,
    error       TEXT,
    attributes  JSONB           NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS helios_spans (
    id          UUID            PRIMARY KEY,
    trace_id    UUID            NOT NULL REFERENCES helios_traces(id) ON DELETE CASCADE,
    parent_id   UUID,
    name        VARCHAR(255)    NOT NULL,
    kind        VARCHAR(50)     NOT NULL,
    start_time  TIMESTAMPTZ     NOT NULL,
    end_time    TIMESTAMPTZ,
    error       TEXT,
    attributes  JSONB           NOT NULL DEFAULT '{}',
    CONSTRAINT fk_spans_parent FOREIGN KEY (trace_id, parent_id)
        REFERENCES helios_spans(trace_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_spans_trace_id UNIQUE (trace_id, id)
);

CREATE INDEX IF NOT EXISTS idx_helios_spans_trace_id
    ON helios_spans(trace_id);

CREATE TABLE IF NOT EXISTS helios_annotations (
    id          UUID            PRIMARY KEY,
    target_id   UUID            NOT NULL,
    label       VARCHAR(255)    NOT NULL,
    rating      SMALLINT,
    comment     TEXT,
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_annotations_target
    ON helios_annotations(target_id);

CREATE TABLE IF NOT EXISTS helios_archive (
    id          UUID            PRIMARY KEY,
    agent_id    VARCHAR(255)    NOT NULL,
    content     TEXT            NOT NULL,
    metadata    JSONB           NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_archive_agent_created
    ON helios_archive (agent_id, created_at DESC);

CREATE TABLE IF NOT EXISTS helios_messages (
    id          UUID            PRIMARY KEY,
    session_id  UUID            NOT NULL,
    role        VARCHAR(20)     NOT NULL,
    content     TEXT,
    tool_calls  JSONB,
    tool_call_id VARCHAR(255),
    tool_name   VARCHAR(255),
    metadata    JSONB           NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_messages_session_id
    ON helios_messages (session_id);

CREATE TABLE IF NOT EXISTS helios_sessions (
    id              UUID            PRIMARY KEY,
    agent_id        VARCHAR(255)    NOT NULL,
    user_id         VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    last_active_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_sessions_agent_user
    ON helios_sessions (agent_id, user_id, last_active_at DESC);
