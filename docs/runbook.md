# Runbook — ingestion

What to check when something looks wrong, and what the numbers behind it mean.

Stage 8 creates the queues and the alarms; this page is what they will be created from, and what
they mean once they exist.

## Queue attributes

| Attribute | Value | Label | Basis |
| --- | --- | --- | --- |
| `VisibilityTimeout` (main) | **30s** | **Reasoned** | The work behind one message is a single database round trip, measured in milliseconds, so this is about a hundred times the expected handling time. It is also the base of the transient-failure backoff, which is why it is not simply as small as possible. |
| `maxReceiveCount` | **3** | **Guess, deliberately low** | A genuinely poisoned message should stop being retried quickly and become visible. Low is only safe because a database outage no longer consumes these — see below. |
| `MessageRetentionPeriod` (main) | **4 days** | **Reasoned** | Must comfortably exceed the longest a message can legitimately be in flight, which is the ~10.5 minute backoff span, and must survive an outage that starts on a Friday evening. |
| `MessageRetentionPeriod` (DLQ) | **14 days** | **SQS maximum** | A message nobody has diagnosed yet is worth keeping for as long as SQS allows. This is the ceiling, not a preference. |
| `ReceiveMessageWaitTimeSeconds` | **20s** | **SQS maximum** | Long polling. An idle consumer at the maximum spends about 129,600 receive requests a month; anything shorter spends more for no benefit. |
| **CloudWatch log retention** | **14 days** | **Derived — must be ≥ DLQ retention** | See below. |

### Log retention is at least DLQ retention, and that is not a coincidence

**DLQ retention: 14 days. Log retention: 14 days.** Side by side, because one constrains the other.

**A message on the dead letter queue carries no reason.** SQS records that it exceeded
`maxReceiveCount`, not what went wrong on any of those receives. The only account of that is the
consumer's log line, correlated by message id.

So if logs expire before the DLQ does, there is a window in which a message is still sitting there,
still redrivable, and **no longer diagnosable**. Raising DLQ retention without raising log retention
opens that window silently. If one of the two numbers moves, the other moves first.

## Redrive

**Redrive is safe, because the consumer is idempotent.** Ingestion deduplicates on the content hash,
so replaying a message that was already processed stores nothing and counts as `unchanged`. There is
no need to work out which DLQ messages were already applied before redriving them.

**The DLQ can contain messages that were processed successfully.** A message is dead-lettered when
it exceeds its receive count, and a receive can fail after the work was done — a crash between
storing and acknowledging, for instance. Do not assume a message on the DLQ represents lost data;
assume it represents an unfinished conversation with SQS.

**Before redriving, read the logs.** Redriving poison simply fills the DLQ again three receives
later. Find the message id in the logs first:

- `poison message <control number> on receive N` — the payload or the contract is wrong. Redriving
  changes nothing until the cause is fixed. A schema version newer than this consumer understands is
  the one case where redrive after a deploy is exactly right.
- `transient failure consuming <control number> ... hiding it for Ns` — the database was unavailable
  and the message was waiting it out. Reaching the DLQ anyway means the outage outlasted about ten
  and a half minutes, and redrive is the correct response once the database is back.

## Health queries

Run against the application database.

**Anything permanently lost:**

```sql
SELECT publication_date, modality_code, state, detected_at
FROM discovery_coverage_gap
WHERE resolved_at IS NULL
ORDER BY publication_date;
```

Empty is the healthy state, which is the point of a separate table. A row means a publication date
left the lookback window without any cycle ever completing it after that date closed, and its
notices were never collected. It is recoverable only by backfill. This is what
`radar.pncp.chunks.expired` alerts on in stage 8.

**Backfills that are stuck:**

```sql
SELECT cycle_date, publication_date, modality_code, state, attempts, last_failure_at, last_failure
FROM discovery_chunk
WHERE origin = 'MANUAL'
  AND completed_at IS NULL
  AND attempts >= 8
ORDER BY publication_date;
```

Eight attempts is a full day of cycles. A manual chunk retries every cycle until it succeeds or
somebody removes it, so without this query a backfill that can never succeed retries forever and
quietly. Each failure is also logged at WARN with its attempt count.

**What the current cycle still owes:**

```sql
SELECT publication_date, modality_code, state, attempts, last_failure
FROM discovery_chunk
WHERE completed_at IS NULL
  AND (cycle_date = (now() AT TIME ZONE 'America/Sao_Paulo')::date OR origin = 'MANUAL')
ORDER BY publication_date, modality_code;
```

Non-empty between cycles is normal — the next attempt is at most three hours away. Non-empty and
unchanged across several cycles is not.

## Backfill

To fetch a publication date again, record a manual chunk and let the next cycle do the work. The
fetching, publishing and completion ordering then exist once rather than twice.

Run `radar-api/src/main/resources/db/operations/backfill-discovery-chunk.sql` with **bound**
parameters — never substituted into the text, and never typed by hand against production. Stage 8
runs it through SSM.

| Parameter | Meaning |
| --- | --- |
| `publication_date` | the Brazilian calendar date to refetch, `yyyy-mm-dd` |
| `modality_code` | 4, 6 or 8 |
| `state` | two-letter code, e.g. `SP` |

It is safe to run twice: the second run resets the chunk rather than failing. A manual chunk is
never reported as a coverage gap, never extends how far back discovery is considered responsible,
and resolves any open gap for that date when it completes.

To stop a backfill that cannot succeed, delete its row.

## Why a database outage no longer fills the DLQ

Worth knowing before changing any of the numbers above.

A redrive policy counts receives and cannot see why a receive failed. With three receives at a plain
30 second visibility timeout, **any database outage longer than about ninety seconds dead-letters
every message in flight** — and an RDS single-AZ maintenance reboot is minutes.

The consumer therefore distinguishes the two; the decision and its alternatives are recorded in
[ADR 0012](adr/0012-transient-failures-extend-visibility.md). A transient failure extends the message's visibility
instead of letting it come straight back: 30s, then 2 minutes, then 8 minutes, so the same three
receives span about ten and a half minutes. Extending visibility does not consume a receive.

Consequences to keep in mind:

- **`maxReceiveCount: 3` is not the ninety-second budget it looks like.** It is about ten minutes
  for transient failures and about ninety seconds for poison.
- **Raising the visibility timeout raises the whole backoff**, since it is the base. Changing it
  from 30s to 60s makes the span twenty-one minutes, not eleven.
- An outage longer than ten and a half minutes still dead-letters, and that is intended: at that
  point an operator should know, and redrive is safe.
