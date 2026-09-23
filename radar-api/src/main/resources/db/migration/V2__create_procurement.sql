-- The procurement notices discovered from PNCP.
--
-- Identity is the PNCP control number itself, not a surrogate key. It is the natural key, PNCP
-- guarantees it, and the whole ingestion rule is "have I seen this control number before" -- a
-- surrogate id would add a second identity that nothing ever queries by.
--
-- There is no index beyond the primary key. The only access path is by control number, which the
-- primary key already serves; an index nothing reads is write cost and disk on a 1 GB box.
--
-- There is no last_seen_at. Nothing reads it, and a column written on every ingestion run that no
-- query consults is a write amplification with no reader.
CREATE TABLE procurement (
    control_number      VARCHAR(64) PRIMARY KEY,
    object_description  TEXT        NOT NULL,
    state               VARCHAR(2)  NOT NULL,
    -- Unconstrained NUMERIC on purpose. A NUMERIC(18,2) would silently round anything PNCP
    -- published with more precision, and a stored value that disagrees with the hash taken over
    -- the payload it came from is the kind of corruption nobody notices for months.
    -- NULL means a sigiloso budget: legitimately absent, not unknown.
    estimated_value     NUMERIC,
    modality_code       INTEGER     NOT NULL,
    modality_name       TEXT        NOT NULL,
    published_at        TIMESTAMPTZ NOT NULL,
    proposal_opens_at   TIMESTAMPTZ NOT NULL,
    proposal_closes_at  TIMESTAMPTZ NOT NULL,
    -- The version token for the conditional writes. Taken over the whole PNCP payload, so it also
    -- covers dataAtualizacaoGlobal. See docs/adr/0007.
    source_payload_hash TEXT        NOT NULL,
    -- PNCP's own change hint. Optional: not every notice carries one.
    source_updated_at   TIMESTAMPTZ,

    -- These mirror invariants the domain already enforces. They are here as a backstop against a
    -- writer that is not this application, not as the primary check.
    CONSTRAINT procurement_object_description_not_blank CHECK (btrim(object_description) <> ''),
    CONSTRAINT procurement_source_payload_hash_not_blank CHECK (btrim(source_payload_hash) <> ''),
    CONSTRAINT procurement_estimated_value_not_negative CHECK (estimated_value IS NULL OR estimated_value >= 0),
    CONSTRAINT procurement_modality_code_positive CHECK (modality_code > 0),
    CONSTRAINT procurement_proposal_window_ordered CHECK (proposal_closes_at >= proposal_opens_at)
);

COMMENT ON TABLE procurement IS
    'PNCP procurement notices. Written by radar-ingestion''s SQS consumer, read by radar-api.';

COMMENT ON COLUMN procurement.source_payload_hash IS
    'Version token for optimistic compare-and-set. Covers the whole PNCP payload.';

COMMENT ON COLUMN procurement.estimated_value IS
    'NULL means the budget is sigiloso, which is legitimate and not the same as unknown.';
