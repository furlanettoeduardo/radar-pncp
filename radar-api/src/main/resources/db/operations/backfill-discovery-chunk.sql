-- Ask discovery to fetch one publication date again.
--
-- This is the backfill procedure. It does not fetch anything itself: it records a MANUAL chunk,
-- and the next scheduled attempt works it like any other pending chunk. That is deliberate --
-- the fetching, the publishing and the completion ordering all stay in one place, exercised by
-- every cycle, rather than existing a second time in an operational path nobody tests.
--
-- Parameters MUST be bound, never substituted into the text. Stage 8's runbook runs this through
-- SSM with bound values; DiscoveryBackfillScriptIT runs this very file against a real schema so
-- it cannot rot.
--
--   :publication_date  the date to refetch, as a Brazilian calendar date (yyyy-mm-dd)
--   :modality_code     4, 6 or 8
--   :state             two letter code, e.g. SP
--
-- Effects worth knowing before running it:
--   * origin becomes MANUAL, so this chunk is never reported as a coverage gap and never extends
--     how far back discovery is considered responsible;
--   * it is retried every cycle until it succeeds or somebody deletes the row;
--   * completing it resolves any open coverage gap for that date, modality and state.
INSERT INTO discovery_chunk (
    cycle_date, publication_date, modality_code, state, origin, planned_at, attempts)
VALUES (
    -- The cycle this request belongs to, in the only timezone PNCP's dates mean anything in.
    (now() AT TIME ZONE 'America/Sao_Paulo')::date,
    :publication_date, :modality_code, :state, 'MANUAL', now(), 0)
ON CONFLICT (cycle_date, publication_date, modality_code, state) DO UPDATE
SET origin            = 'MANUAL',
    completed_at      = NULL,
    pages_fetched     = NULL,
    notices_published = NULL,
    last_failure      = NULL,
    last_failure_at   = NULL,
    attempts          = 0;
