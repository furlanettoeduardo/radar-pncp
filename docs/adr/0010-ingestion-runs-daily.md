---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Ingestion runs daily, because PNCP has no finer granularity to offer

## Context and Problem Statement

The discovery job polls PNCP for recently published notices. How often it should run looked like a
matter of taste — more often is fresher, less often is politer — until the API's own shape was
examined, at which point it stopped being a preference.

## The constraint that decides it

**PNCP queries by calendar date.** `dataInicial` and `dataFinal` are `yyyyMMdd`, with no time
component and no cursor. The narrowest window obtainable is therefore **one whole day**, and *every
run fetches at least a full day of pages regardless of how recently the last one ran.*

Running more often does not fetch less. It fetches the same pages again.

## Considered Options

- **Daily.**
- **Every six hours.**
- **Hourly.**

## Decision Outcome

Chosen option: **daily**.

### What each option costs

Volume is counted, not assumed. Measured 2026-09-23, all on the **same** window — SP, 2026-09-15 to
2026-09-21, `tamanhoPagina=10` — so the modalities are comparable to each other:

| Modality | Records | Pages | Pages per day | Basis |
| --- | --- | --- | --- | --- |
| 6, Pregão eletrônico | 1,707 | 171 | **24.4** | Measured over the seven day window |
| 8, Dispensa | 3,438 | 344 | **49.1** | Measured over the seven day window |
| 4, Concorrência eletrônica | — | — | **≤ 4** | **Estimate.** The seven day query failed four times against a degraded PNCP; the bound comes from 38 records on 2026-09-17 alone |
| **SP, all three** | | | **78–81** | |

Modality 6's figure supersedes an earlier reading of 1,697 records. The difference is late
publication into the same window, which is itself the confirmation that the two readings describe
the same seven days.

**An earlier version of this table understated modalities 4 and 8 by roughly seven times.** Their
samples were fetched by hand for a single day while modality 6's covered a week, and the totals
were compared as though they were alike. The sample files now carry that window in their
`.headers.txt`, with the warning, so the comparison cannot be made again by accident.

Only modality 6 is configured today, so a run costs **24.4 pages a day**:

| Schedule | Runs per day | Pages fetched per day |
| --- | --- | --- |
| **Daily** | 1 | **73** |
| Every 6 hours | 4 | 293 |
| Hourly | 24 | 1,757 |

Seventy-three rather than twenty-four because a run fetches `lookback-days + 1` calendar days:
`dataInicial` and `dataFinal` are **inclusive** bounds and the window is `today-2` through `today`.
The margin test counted `lookback-days` alone and so understated every run by a third; at the
measured volumes that error was the difference between the check passing and failing.

Request cost scales linearly with frequency and the data returned does not.

### What the extra frequency would buy

Almost nothing, and the samples say so. Time from publication to proposal close, across every
recorded notice: **10, 13, 14, 20 and 44 days — a median of 14.**

A notice discovered up to a day late has lost about 7% of the window a supplier had to act in. That
is a real cost and it is small next to fetching the same pages twenty-four times. The proposal
deadline rule already grades urgency, so a slightly older notice is scored slightly lower rather
than missed.

Against a public API run by a public body, which this project has already watched return 504 under
load, twenty-four times the traffic for 7% of a two-week window is not a trade worth making.

## What would change this answer

Recorded so that a future reader knows what to watch for rather than re-deriving it:

- **PNCP exposing a timestamp-based or cursor-based filter.** The one-day floor is the entire
  argument. If a run could ask for "everything since 14:05" the cost of frequency would collapse and
  sub-daily would become worth reconsidering immediately.
- **The median proposal window collapsing.** At 14 days, a day of latency is 7%. If typical windows
  shortened to three or four days, the same latency would be 25–33% and the trade would invert.
- **Volume falling far enough that a day is a handful of pages.** Unlikely, and the opposite is the
  direction to expect.

## The coupling this created, and why it is gone

**While one run was one fan out**, `radar.discovery.lookback-days` and the page cap could not move
alone. A wider window meant more pages inside a single capped operation, and a lookback wide enough
to exceed the cap would have thrown on *every* run and never once succeeded — a configuration
deadlock a scheduled job would surface at an unhelpful hour.

**Chunking severs it.** The cap is per chunk, and a chunk is one publication date of one modality in
one state. The width of the window cannot change the size of the largest chunk.

What bounds the lookback now:

- **the run budget**, since every chunk of the window is still worked in one invocation;
- **politeness**, since every extra day is a full day of pages against a public API, every day,
  forever.

Neither is a deadlock. Exceeding them degrades a run rather than making every run impossible, which
is why `ChunkAndRunFitTheirBudgetsTest` asserts both with a margin rather than exactly:

| Invariant | At the current configuration |
| --- | --- |
| largest chunk at peak, x1.5, under the per-chunk cap | 71 pages x 1.5 = **106 against 500** |
| a peak run, at 8 at a time and the slowest page ever measured, x1.5, under the deadline | **about 87s against 5 minutes** |

A third test asserts the decoupling itself, so nobody quietly reintroduces it.

## The lookback is 3, and it is sized for reliability

`lookback-days: 3`, which is **four calendar days** per run because `dataInicial` and `dataFinal`
are inclusive.

The number follows from what coverage means. A publication date keeps gaining notices all day, so a
fetch made while the date is still open cannot be the last word on it — modality 6 for
2026-09-15..21 was 1,697 records when first sampled and 1,707 when measured the next day. **Only a
cycle that began after a date closed settles it.** So a lookback of N buys N covering cycles, not
N+1:

| Lookback | Window (calendar days) | Covering cycles for a date | Consecutive lost days survived |
| --- | --- | --- | --- |
| 2 | 3 | P+1, P+2 | 1 |
| **3** | **4** | **P+1, P+2, P+3** | **2** |
| 4 | 5 | P+1 … P+4 | 3 |

Two survivable bad days rather than one, for about 33% more pages at peak. The likeliest bad day is
**not PNCP**: it is this project's single t3.micro being down for an OOM or a broken deploy, and
that is a class of outage that lasts hours rather than minutes.

It is affordable only because chunking decoupled the lookback from the cap. The largest chunk stays
at 71 pages whatever the window is; only the run grows, and a peak run is about a minute against a
five minute budget.

**What revisits this number:** the late-arrival distribution, `radar.pncp.notices.late_arrival`,
which records the age in days of every newly inserted procurement whose publication date is older
than yesterday, in fixed buckets of 1, 2, 3, 4, 5, 7 and 14 days. When there is a month of it, the
question "what share of late arrivals would a lookback of N have caught" is a reading rather than
an argument. Until then this is a judgement about outages, and it says so.


## The design limit: national scope needs a different invocation shape

Recorded here rather than asserted in a test, because it describes a system this one is not, and a
test that fails for a configuration nobody has chosen only teaches people to loosen the test.

At the measured SP volumes and the pessimistic national multiplier of six, a national run costs:

| Configured scope | Modalities | Pages per run (3 day window) |
| --- | --- | --- |
| **SP** | 6 | **73** |
| SP | 4, 6, 8 | 233 |
| Nationally | 6 | 440 |
| Nationally | 4, 6, 8 | **1,396** |

So national scope over the full modality list needs roughly **three times the entire fan out cap**,
and the cap is not the thing to raise: 1,396 pages at 8 concurrent requests is already minutes of
sustained load against a public API that fails routinely.

**At national scope the invocation shape must change.** Four starting points, in rough order of how
much they disturb:

1. **Chunk the window.** One invocation per calendar day rather than one per run. The cap then
   bounds a day instead of a window, and a failed day is retried independently of its neighbours.
   Cheapest change, and it composes with the same-day re-attempt below.
2. **Fan out per state.** Twenty-seven runs, each with its own cap and budget, each small enough to
   fail alone. PNCP already takes a single `uf`, so this costs no extra requests — only scheduling.
3. **Raise the page size to 50.** Verified supported; five times fewer requests. Not free, and the
   arithmetic is below.
4. **Make page requests messages.** The walk becomes queue-driven, so there is no fan out to cap and
   no whole-operation deadline to miss. The largest change by far, and the only one that removes the
   cap rather than dividing by it.

None of these is chosen here. They are recorded so that the first person to need national coverage
starts from the arithmetic rather than from the cap.

## Page size: 50 works, and 10 stays

Verified against PNCP on 2026-09-23: `tamanhoPagina=50` returns 50 records on the page, and modality
8 over the same seven day window came back as **69 pages instead of 344**.

Kept at 10 anyway, because the saving is not what it looks like:

| | page size 10 | page size 50 |
| --- | --- | --- |
| Pages, mod 8, 7 days, SP | 344 | 69 |
| Time to first byte | 0.35–1.04s (n=5) | **3.7s** |
| Headroom under the 10s read timeout | ~10x | **~2.7x** |

Latency scales roughly with page size, so **five times fewer requests is not five times less server
time** — it is close to the same total work, moved into fewer, slower calls. What it does change is
the headroom: at 3.7s a normal response, the 10s read timeout stops being ten times the normal case
and becomes under three, on an API already observed hanging and returning 504.

Revisit with production latency data in stage 8. If the volume ever needs it, raising the page size
and the read timeout together is one decision, not two.

## Freshness cadence and attempt cadence are different things

"Daily is enough" is a statement about **freshness** and it survives everything above. It is not a
statement about **attempts**, and one attempt per day is fragile against the PNCP this project has
actually observed: on 2026-09-23, eleven of seventeen calls to `contratacoes/publicacao` failed.
One afternoon is not a baseline, but a single daily attempt against an API failing at that rate
loses whole days to bad luck.

A failed run loses everything or nothing — never part. `StructuredFanOut` throws rather than
returning what it managed, so `ProcurementDiscoveryJob` never reaches its publish loop and a failed
run publishes **zero** messages. That makes a re-attempt simple to reason about: there is no partial
state to reconcile, only a run that did not happen.

### Proposed: attempt every three hours, fetch at most once a day

Not yet implemented; recorded here so the schedule work starts from it.

- **Trigger every three hours**, eight attempts a day, each guarded by ShedLock so two instances
  never run together.
- **Before fetching, check whether today's window has already been fetched successfully.** If it
  has, return immediately without touching PNCP.
- **Record the successful run** when, and only when, the fetch and the publish both complete. A
  failure records nothing, so the next trigger retries.

What it costs when PNCP is healthy: one real run of 73 pages, plus seven cheap no-ops that read one
row and exit. **Sub-daily attempts do not multiply request cost** — which was the entire objection
to sub-daily *scheduling* — because the guard is on success, not on the trigger.

What it costs when PNCP is down all day: at most eight attempts, about 584 page requests. That is
the worst case, it only happens on a day that would otherwise have been lost entirely, and it is
still smaller than a single national run.

**Why not ShedLock's `lockAtLeastFor`.** Holding the lock for twenty hours after a run would give
the same "once a day" behaviour with no new state, and it is wrong: the lock is taken when a run
*starts*, so a run that fails immediately would still suppress every attempt for the rest of the
day. The guard has to be on the outcome.

**Why not an in-process retry loop.** A JVM sleeping between attempts holds its lock across the
gap, cannot be restarted without losing the run, and loses the schedule entirely if it crashes.
Persisted state means a restart resumes correctly, which on a single t3.micro is not a theoretical
concern.

**What it needs:** one small table recording the last successfully completed window. Per the module
rules, Flyway migrations are owned by `radar-api`, so the DDL for a table written by
`radar-ingestion` lives in the other module — the same split ShedLock's own table already has, and
worth stating rather than discovering.

## The chunk is the unit of success

Decided after the reliability numbers, not before. On 2026-09-23, **36 of 42 calls** to
`contratacoes/publicacao` failed, in two distinct modes: fast 5xx, and zero-byte hangs. An earlier
sample the same afternoon was 11 of 17. Modality 4's seven-day query failed four times, and a
day-by-day retry hit **21 consecutive zero-byte hangs at 60s across all seven days** — including
2026-09-17, which had answered in 0.73s earlier that same day. That rules out a poison day and
leaves PNCP degradation.

Under all-or-nothing, a run needed **every** page to succeed. Over 235 to 335 pages, with failures
that are not perfectly clustered, run success falls off exponentially with page count. And it bought
nothing: the consumer deduplicates on content hash, so a partial publish is safe and a repeated
chunk is a no-op.

**A chunk is one PNCP query — one publication date, one modality, one state — paginated to
completion.**

- A chunk publishes when it completes. A failed chunk publishes nothing: the first-failure latch
  stays, scoped to the chunk.
- **Success is recorded after publishing, never before.** A crash between the two refetches and
  republishes, which the consumer absorbs; the reverse order loses notices silently.
- The three-hourly attempt refetches only the chunks that have not succeeded, not the window.
- The cap applies per chunk, so the margin problem disappears by construction rather than by
  raising it.

Under chunks, the pages that succeeded on a bad afternoon would have been kept. Under
all-or-nothing they were discarded.

### A date that is lost must not be lost quietly

A publication date that leaves the window without a single covering fetch is gone. It gets an ERROR
line naming the date, and a `radar.pncp.chunks.expired` counter tagged by **modality and state**,
both bounded — the date goes in the message and in the table, because a metric tag whose values grow
forever is a slow memory leak on a box with a gigabyte to spend. Stage 8 alerts on the counter.

Detection **generates the calendar** rather than reading the rows that happen to exist. A service
down for five days never planned those dates at all, so a check over existing rows would report
nothing and lose the days in silence — which is exactly the failure a lookback of 3 is there to
survive. Responsibility starts per modality and state at the first date each was scheduled for, so
configuring a new modality does not flag its entire history, and it counts scheduled rows only, so
one backfill of an old date does not report every date since as lost.

The backfill procedure is a versioned, parameterised script in the repository
(`db/operations/backfill-discovery-chunk.sql`), run with **bound** parameters and never typed by
hand against production; stage 8's runbook runs it through SSM. It records a MANUAL chunk and lets
the next cycle do the work, so the fetching, publishing and completion ordering exist once rather
than twice. `DiscoveryBackfillScriptIT` executes that very file against a real schema, so it cannot
rot.

## Per-page retries stay at 300-900ms

Recorded here because the alternative is tempting and wrong.

`maxAttempts(3)` with `ofExponentialRandomBackoff(200ms, 2.0, 0.5)` gives two waits of 100-300ms and
200-600ms: **300-900ms of backoff**, and a whole retry series of about **1.5s** against the fast 5xx
that dominate. PNCP was observed recovering once after two failures 15 seconds apart, and once not
at all after two minutes. Stretching the retry to cover 30 seconds would catch one of those two and
neither reliably.

It stays short because:

1. the fan out holds a concurrency permit for the whole series, so eight pages each waiting out 30s
   turns a fast honest failure into a slow one;
2. the breaker opens after ten recorded calls, so during a real outage only the first handful of
   pages get their full budget however generous it is;
3. **riding out an outage is the schedule's job.** A three-hourly re-attempt covers both the
   15-second case and the two-minute case, and costs nothing when PNCP is healthy.

### Consequences

- Good, because the schedule follows from the API's shape rather than from a preference, so it can
  be defended without appeal to taste.
- Good, because daily keeps traffic against a public service proportionate to what it can actually
  tell us.
- Bad, because a notice published just after a run waits up to a day. Accepted at a 14-day median
  window, and revisited if that median moves.
- Bad, because two configuration values are now coupled, and the coupling lives in a test rather
  than in a type. A single record holding both would be stronger; it is not worth the indirection
  for two numbers that are read from different property namespaces by different components.
