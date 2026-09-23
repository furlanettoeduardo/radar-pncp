-- The unit of discovery work, and the permanent record of what discovery lost.
--
-- A chunk is one PNCP query: one publication date, one modality, one state, paginated to
-- completion. It replaces the whole run as the unit of success, because a run of 300-odd pages
-- that discards everything when any one page fails succeeds exponentially rarely against an API
-- observed failing 36 of 42 calls in an afternoon.
CREATE TABLE discovery_chunk (
    -- The Sao Paulo calendar day this attempt belongs to. In the key, because a publication date
    -- must be refetched once per cycle: a date fetched while it is still open keeps gaining
    -- notices afterwards, and completion-once would never look again.
    cycle_date        DATE        NOT NULL,
    publication_date  DATE        NOT NULL,
    modality_code     INTEGER     NOT NULL,
    state             VARCHAR(2)  NOT NULL,

    -- SCHEDULED chunks are planned by the job. MANUAL chunks are backfills asked for by a human,
    -- and the difference matters twice: a manual chunk is never reported as a gap, and it never
    -- extends how far back we are considered responsible.
    origin            VARCHAR(16) NOT NULL,
    planned_at        TIMESTAMPTZ NOT NULL,
    attempts          INTEGER     NOT NULL DEFAULT 0,

    -- Set only after every message of this chunk has been published. Never before: a crash between
    -- publishing and this write refetches and republishes, which the consumer absorbs, while the
    -- reverse order loses notices with no record that they ever existed.
    completed_at      TIMESTAMPTZ,
    pages_fetched     INTEGER,
    notices_published INTEGER,

    last_failure_at   TIMESTAMPTZ,
    last_failure      TEXT,

    PRIMARY KEY (cycle_date, publication_date, modality_code, state),

    CONSTRAINT discovery_chunk_origin_known
        CHECK (origin IN ('SCHEDULED', 'MANUAL')),
    -- A cycle cannot fetch a publication date that has not happened yet.
    CONSTRAINT discovery_chunk_cycle_not_before_publication
        CHECK (cycle_date >= publication_date),
    CONSTRAINT discovery_chunk_attempts_not_negative
        CHECK (attempts >= 0),
    -- A completed chunk carries its counts; an incomplete one carries neither.
    CONSTRAINT discovery_chunk_completion_is_whole
        CHECK ((completed_at IS NULL AND pages_fetched IS NULL AND notices_published IS NULL)
            OR (completed_at IS NOT NULL AND pages_fetched IS NOT NULL
                AND notices_published IS NOT NULL))
);

COMMENT ON TABLE discovery_chunk IS
    'One PNCP query per row: a publication date, a modality and a state, for one cycle.';

COMMENT ON COLUMN discovery_chunk.cycle_date IS
    'The Sao Paulo day this attempt belongs to. cycle_date > publication_date is what makes a '
    'completed chunk count as coverage: it proves the fetch started after the date closed.';

COMMENT ON COLUMN discovery_chunk.completed_at IS
    'Written only after publishing. The success marker is a nullable timestamp rather than a '
    'status column so that it cannot disagree with itself.';

-- A publication date we never covered, and never will: it left the lookback window without a
-- single successful fetch that started after it closed.
--
-- A separate table because a gap is about a publication date while a chunk is about one attempt at
-- one, and with cycle_date in the chunk key there is no row that represents the former. It is also
-- empty when everything is well, which makes "is anything lost" a count rather than a judgement.
CREATE TABLE discovery_coverage_gap (
    publication_date DATE        NOT NULL,
    modality_code    INTEGER     NOT NULL,
    state            VARCHAR(2)  NOT NULL,
    detected_at      TIMESTAMPTZ NOT NULL,
    -- Set when a later backfill finally covers the date. Kept rather than deleted: a system that
    -- erases the record of what it lost cannot be asked how often it loses things.
    resolved_at      TIMESTAMPTZ,

    PRIMARY KEY (publication_date, modality_code, state)
);

COMMENT ON TABLE discovery_coverage_gap IS
    'Publication dates that left the window uncovered. One row per permanent loss; the primary '
    'key is what makes the alert fire exactly once.';

-- No indexes on either table, deliberately. Nine chunks a cycle is roughly 3,300 rows a year, and
-- the coverage query runs once per cycle; a sequential scan of that is faster than maintaining an
-- index on every write. The same reasoning as the procurement table in V2.
