-- Foundation stage: no business schema exists yet. This table proves that Flyway is wired,
-- reaches PostgreSQL and applies migrations on startup. Later stages replace it.
CREATE TABLE schema_version_smoke (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stage      VARCHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE schema_version_smoke IS
    'Smoke table proving the Flyway pipeline works. Carries no business data.';

INSERT INTO schema_version_smoke (stage) VALUES ('stage-01-foundation');
