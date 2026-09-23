-- ShedLock's lock table, in this module because radar-api is the single owner of the schema, even
-- though radar-ingestion is the only service that takes the lock.
--
-- The column names and types are ShedLock's, not ours: its JdbcTemplate provider selects and
-- updates by these exact names. The only choice here is TIMESTAMPTZ over TIMESTAMP, which matters
-- because the two services and the database can disagree about the local zone and never about an
-- instant.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS
    'ShedLock mutual exclusion for scheduled work. Guards concurrency only: whether a run is due '
    'is answered by discovery_chunk, because a lock is taken when a run starts and says nothing '
    'about whether it succeeded.';
