# Configuration, and which of it is evidence

Every tunable number in this system, with an honest label: **evidenced** by something recorded in
`docs/samples`, **estimated** from something evidenced, or **guessed** from convention.

This page exists because a repository that says which of its numbers are guesses is more useful
than one that presents all of them as engineering. Two of the defects in this codebase were numbers
that looked evidenced and were not — see [ADR 0007](adr/0007-canonical-content-hash-for-change-detection.md)
and [ADR 0008](adr/0008-virtual-threads-and-structured-concurrency-for-page-fetching.md).

## PNCP adapter — `radar.pncp.*`

| Setting | Default | Label | Basis |
| --- | --- | --- | --- |
| `page-size` | `10` | **Measured, and deliberately not raised** | PNCP also accepts `tamanhoPagina=50`, verified 2026-09-23: modality 8 over the same week came back as 69 pages instead of 344. Kept at 10 because **latency scales with page size** — time to first byte was 3.7s at 50 against 0.35–1.04s at 10 — so 5x fewer requests is roughly the same total server time in fewer, slower calls, and it cuts read-timeout headroom from ~10x to ~2.7x. Revisit with production latency in stage 8. See [ADR 0010](adr/0010-ingestion-runs-daily.md). |
| `max-pages-per-chunk` | `500` | **Measured against the largest chunk** | Caps *one* chunk: one publication date, one modality, one state. The largest is a peak day of modality 8 at about **71 pages**, so 500 is roughly 7x it. Renamed from `max-total-pages` when the cap stopped bounding a whole run; the value did not change. It no longer moves with the lookback — see [ADR 0010](adr/0010-ingestion-runs-daily.md). |
| `modality-codes` | `[4, 6, 8]` | **Two measured, one estimated** | Measured 2026-09-23 on SP over 2026-09-15..21: modality 6 is 24.4 pages a day, modality 8 is 49.1. **Modality 4 is an estimate** bounded at 4 a day from a single Thursday: its seven-day query failed four times, and a day-by-day retry hit 21 consecutive zero-byte hangs. Chunking makes that safe to configure — an underestimate fails one chunk against its cap instead of truncating a run. `ObservedPageVolume` refuses to price a modality nobody has measured at all. |
| `max-concurrent-requests` | `8` | **Guess** | No PNCP rate-limit documentation was consulted and nothing in the samples speaks to it. 8 is a conventional polite number. |
| `connect-timeout` | `2s` | **Measured (n=5, 2026-09-23)** | Observed connect took 0.05–0.09s. 2s is over 20x the slowest measurement, which is generous on purpose: a connect that is merely slow should not fail, and a connect that never completes is what this bounds. |
| `read-timeout` | `10s` | **Measured (n=5, 2026-09-23)** | Time to first byte on the consulta endpoint was 0.35–1.04s at `page-size: 10`. 10s is about 10x the slowest normal response. Separately, two calls hung with zero bytes for over 60s and for about 7 minutes — that is what this value exists to bound, and the reason to keep it low rather than raise it. Raising the page size would eat this headroom; see the row above. |
| `operation-deadline` | `5m` | **Guess, with arithmetic behind it** | A healthy run at the cap is about 500 pages over 8 at a time, roughly a minute; this leaves five times that. The number it is protecting against is real: 500 pages × 3 attempts × a 10s read timeout is over half an hour for one invocation. **It used to mean ten minutes rather than five** — discovery runs in two phases and each opened its own budget. Fixed, and guarded by `PncpRunIsBoundedTest`. |
| `--enable-preview` | on the Dockerfile `ENTRYPOINT` | **Not a tunable** | `StructuredTaskScope` is a preview API in Java 21 and the image cannot run without the flag. Deliberately *not* in `JAVA_TOOL_OPTIONS`: docker compose sets that variable and replaced it wholesale, which stripped the flag and broke startup once. An entrypoint is the one place no orchestrator clobbers by accident. Guarded by `PreviewFlagWiringTest` at build time and `PreviewFeatures.requireEnabled()` at boot. See [ADR 0008](adr/0008-virtual-threads-and-structured-concurrency-for-page-fetching.md). |
| `user-agent` | project + repo URL | n/a | Not a tunable. PNCP is run by a public body and being identifiable costs nothing. |

### Measurement, 2026-09-23: normal latency, two hangs, and a 65% failure rate

Five calls to `contratacoes/publicacao`, at `page-size: 10`:

| | connect | time to first byte |
| --- | --- | --- |
| range across 5 calls | 0.05–0.09s | 0.35–1.04s |

Separately, **two calls accepted the connection and sent zero bytes** — once for over 60 seconds, once
for nearly 7 minutes — and the same query answered in about a second minutes later.

**Of 42 calls made to that endpoint on 2026-09-23, 36 failed**, in two distinct modes: fast 5xx,
and zero-byte hangs. An earlier sample of 17 that afternoon had 11 failures and broke down as:

| Failure | Count |
| --- | --- |
| HTTP 504 | 5 |
| HTTP 502 / 503 | 3 |
| Zero-byte hang (>60s, and ~7min) | 2 |
| HTTP 500 | 1 |
| **Total failed** | **11 of 17** |

One 504 took **70 seconds** to arrive, which puts PNCP's own gateway give-up at about a minute — so
a client read timeout above 60s would be waiting for a gateway that has already stopped waiting.

Modality 4's seven-day query failed four times, and a day-by-day retry hit **21 consecutive
zero-byte hangs at 60s across all seven days** — including 2026-09-17, which had answered in 0.73s
earlier the same day. That rules out a poison day and leaves PNCP degradation.

**This is the measurement that decided the chunk design.** Under all-or-nothing a run needed every
one of 235 to 335 pages to succeed; at this failure rate, and with failures that are not perfectly
clustered, that succeeds exponentially rarely. Under chunks the pages that did succeed are kept.

Recovery was inconsistent: one retry series **recovered after two failures 15 seconds apart**, and
another **had not recovered after about two minutes**. One afternoon is not a baseline. What it is
enough to conclude is that **PNCP instability is routine rather than exceptional**, which is an
argument about the *schedule* — see [ADR 0010](adr/0010-ingestion-runs-daily.md) — and not about
these two numbers.

That pair of facts is what promotes both timeouts from convention to measurement. A normal response
arrives in about a second, so 10s is roughly ten times the slowest observed; and the hangs are
precisely the failure a read timeout exists for. **The hangs argue for keeping the value low, not for
raising it**: a client that waits out a 7-minute silence has turned a fast failure into a stalled
run.

### Observation, 2026-09-22: the 504 that started the investigation

Not evidence for a timeout value. Recorded because it is evidence of something else.

On 2026-09-22 the live smoke test began failing against `contratacoes/publicacao`. Investigation
from outside the JVM found:

- a direct request to the same URL returned **504 Gateway Timeout after about 70 seconds**;
- TCP to `pncp.gov.br:443` connected normally, so the host was reachable and the failure was
  upstream of the origin rather than a network problem;
- the same query had succeeded roughly an hour earlier, returning `totalRegistros: 1891`.

**This is not a basis for tuning `read-timeout`.** A 504 from a gateway is unavailability, not
latency: the origin never answered at all, so the number 70 measures how long PNCP's own
infrastructure waits before giving up, not how long a healthy response takes. Raising a client
timeout to accommodate it would record false evidence and would make a healthy client wait longer
for answers that are not coming.

What it *is* evidence for: **PNCP has bad days.** The adapter behaved correctly — it bounded the
wait, retried, exhausted its budget and failed in about 61 seconds, which is three read timeouts
plus backoff. Worth carrying into stage 4: the scheduler must expect an entire invocation to fail,
and must not treat a failed run as an empty day.

## Discovery — `radar.discovery.*`

| Setting | Default | Label | Basis |
| --- | --- | --- | --- |
| `lookback-days` | `3` | **Judgement, with measured cost and a plan to replace it** | Four calendar days per run, since the bounds are inclusive. A date is covered only by a cycle that ran *after* it closed, so a lookback of N buys N covering cycles: 3 survives **two** consecutive lost days. Sized against this project's single instance being down, not against PNCP. No longer coupled to the cap. The `late_arrival` distribution replaces this judgement with a reading; see [ADR 0010](adr/0010-ingestion-runs-daily.md). |
| `states` | `[SP]` | **Product decision, and load-bearing** | SP is the scope this project serves. It was `[]`, meaning every state, which PNCP accepts as an omitted `uf`. The configured scope is what the budget tests price, and the discovery job now **refuses to start** with an empty set rather than attempting a national run: that needs a different invocation shape, recorded as a design limit in [ADR 0010](adr/0010-ingestion-runs-daily.md). |

### What the lookback overlap actually costs

An earlier note in this repository said re-reading a day "costs nothing, because the consumer
deduplicates on content hash". **That is true for storage and false for requests**, and the
difference matters because PNCP is a public API that has already been observed falling over.

Measured volume, SP at `page-size: 10`, all on the week of 2026-09-15..21 (measured 2026-09-23):

| Modality | Pages | Pages per day | Configured? |
| --- | --- | --- | --- |
| 6, Pregão eletrônico | 171 | **24.4** | yes |
| 8, Dispensa | 344 | **49.1** | no |
| 4, Concorrência eletrônica | — | **≤ 4** (estimate) | no |

**A lookback of 2 days is 3 calendar days of pages, not 2.** `dataInicial` and `dataFinal` are
inclusive and the window is `today-2` through `today`. Counting 2 was an arithmetic error in the
margin test, and at these volumes it was the difference between the check passing and failing.

| | pages per run |
| --- | --- |
| One day, SP, modality 6 | 24 |
| **The configured window (3 calendar days)** | **73** |
| The overlap alone, per run | **49** |

So the overlap is **200 percent overhead on the minimum** — two redundant days on every run, not
one. It is still worth paying: a single missed run without it loses a day of notices permanently,
and a lost notice is the one failure this system exists to prevent. Given how often PNCP was
observed failing, that redundancy is the cheapest insurance here. But it is a real recurring cost
against somebody else's infrastructure and it should be stated as one.

### Why the schedule is daily, and what sub-daily would cost

Decided in [ADR 0010](adr/0010-ingestion-runs-daily.md), which also records what would change the
answer and the lookback/cap coupling below.

**PNCP's query granularity is a calendar date.** `dataInicial` and `dataFinal` are `yyyyMMdd`, so the
narrowest window obtainable is one whole day. A run at any interval therefore fetches at least a
full day of pages, and running more often than daily re-fetches the same pages:

| Schedule | Runs per day | Pages per day |
| --- | --- | --- |
| **Daily** | 1 | **73** |
| Every 6 hours | 4 | 293 |
| Hourly | 24 | 1,757 |

Sub-daily *scheduling* multiplies request cost linearly and buys at most one day of freshness,
against a median proposal window of 14 days. Daily is the interval the API's own granularity argues
for.

**Attempting more often is a different question from fetching more often**, and one attempt a day is
fragile against an API that failed 11 of 17 calls in an afternoon. ADR 0010 proposes attempting
every three hours while still fetching at most once a day, guarded on whether today's window has
already succeeded. That leaves this table unchanged when PNCP is healthy.

### The window and the fan-out cap are checked against each other

A lookback wide enough to need more pages than `max-total-pages` would throw on **every** run and
never once succeed — a configuration deadlock. Two things close it:

- At runtime, `PncpFanOutTooLargeException` already says to narrow the date range rather than raise
  the cap, so the failure is at least legible.
- At build time, `DiscoveryWindowFitsTheFanOutCapTest` binds the real configuration and prices the
  **configured** scope through `ObservedPageVolume`, failing if the window exceeds the cap or comes
  within a 1.5x margin of it.

`ObservedPageVolume` **refuses to estimate.** A state or a modality nobody has ever measured makes
it throw — *"no page volume has ever been observed for RJ, and SP's figures are not a stand in for
it. Measure it before configuring it."* — so widening the scope fails the build rather than being
discovered from the traffic. That refusal replaced a national extrapolation which had been gating a
configuration that was not national.

**The current margin is comfortable, and it is comfortable because the scope is one state.** The
defaults need 73 pages of a 500 cap, clearing the 1.5x assertion at 110. The same window nationally
needs about 440, which passes the cap and fails the margin — which is why national scope is recorded
in ADR 0010 as a limit requiring a different invocation shape, rather than as a bigger cap.

## Resilience — hardcoded in `PncpPageClient`

| Setting | Value | Label |
| --- | --- | --- |
| retry attempts | `3` | **Guess** — convention |
| retry backoff | `200ms`, ×2, jitter `0.5` | **Guess** — convention, and now measured against observed PNCP recovery. See below. |
| breaker sliding window | `20` calls | **Guess** |
| breaker minimum calls | `10` | **Guess** |
| breaker failure threshold | `50%` | **Guess** |
| breaker open duration | `30s` | **Guess** |

These four breaker values are smaller than Resilience4j's defaults (100 / 100 / 50% / 60s) because
per-invocation call volume here is low. That reasoning is sound and the numbers are still taste.

### How long the retries actually cover

`maxAttempts(3)` with `ofExponentialRandomBackoff(200ms, 2.0, 0.5)` gives two waits, each
randomised ±50%:

| | base | actual range |
| --- | --- | --- |
| wait after attempt 1 | 200ms | 100–300ms |
| wait after attempt 2 | 400ms | 200–600ms |
| **total backoff** | 600ms | **300–900ms** |

Wall clock for the whole series depends entirely on how PNCP fails:

| Failure mode | Time per attempt | Whole retry series |
| --- | --- | --- |
| Fast 5xx (500/502/503) | ~0.3s | **~1.5s** |
| Zero-byte hang, or a 504 slower than the read timeout | 10s | **~30.6s** |

**So against the failures that actually dominate, the retry series is over in about a second and a
half** — well inside any outage. The 30s figure only appears when the read timeout is doing the
work, which is an accident of that timeout rather than a retry budget.

Measured against what PNCP did on 2026-09-23: one series **recovered after two failures 15 seconds
apart**, which this configuration would have missed by an order of magnitude; another **had not
recovered after two minutes**, which no per-page retry should be trying to cover.

**Recommendation: do not stretch this to 30 seconds.** Reasons, in order of weight:

1. It would cover one of the two observed recoveries and neither is a baseline.
2. The fan out holds a concurrency permit for the whole series. Eight pages each waiting out 30s
   turns a fast, honest failure into a slow one, and the first-failure latch means the run is
   already doomed by then anyway.
3. The circuit breaker opens after 10 recorded calls, so during a real outage only the first handful
   of pages get their full retry budget regardless of how generous it is.
4. **Riding out an outage is the schedule's job, not the retry's.** A three-hourly re-attempt covers
   both the 15-second case and the two-minute case, and costs nothing when PNCP is healthy. See
   [ADR 0010](adr/0010-ingestion-runs-daily.md).

If the per-page budget is to move at all, the defensible change is small: initial backoff `200ms` →
`1s`, keeping 3 attempts and ×2, giving 1.5–4.5s. That covers a genuine blip without pretending to
survive an outage. **Not applied — it is a change to a configured number and wants a decision.**

### What an open circuit does to a run in progress

The breaker records each *attempt*, not each fetch, because it sits inside the retry. At a 65%
failure rate it reaches its 10-call minimum within roughly the first two attempt-rounds of the eight
concurrent first-page fetches, and opens.

**Nothing published, nothing half-done.** `StructuredFanOut` throws rather than returning what it
managed, so `PncpProcurementSource.fetch` throws, so `ProcurementDiscoveryJob` never reaches its
publish loop. A failed run publishes **zero** messages. There is no partial state to reconcile —
only a run that did not happen.

**The lookback recovers it.** A notice published on day D is inside the window of the runs on D,
D+1 and D+2, because the window is three calendar days:

| Run day | Window | Covers D? |
| --- | --- | --- |
| D | D-2 … D | yes |
| D+1 | D-1 … D+1 | yes |
| D+2 | D … D+2 | yes |
| D+3 | D+1 … D+3 | **no** |

So **two consecutive failed days lose nothing**: the run on D+2 still covers D. The cost is
freshness — a notice found two days late has lost about 14% of a 14-day proposal window, and the
deadline rule scores it slightly lower rather than missing it.

**The third consecutive failed run is the one that loses data**, and it loses day D permanently and
silently. That is the real argument for re-attempting within the day rather than for a wider
lookback: three failed days in a row is unlikely, and a lost notice is the one failure this system
exists to prevent.

### Considered and deferred: lowering `minimumNumberOfCalls`

Raised on 2026-09-22 and deliberately not done.

At 10, the breaker cannot open until ten calls have been recorded. The breaker sits inside the
retry, so each attempt counts, and eight concurrent page fetches reach ten around their second
attempt — by which point the fan out has usually already cancelled itself, because the first job to
exhaust its retries kills the run. **Within a single invocation the breaker opens at roughly the
moment it has stopped mattering.** Lowering it to about 4 would make it bite during a fan out
rather than after one.

It was deferred because of what it trades. A genuinely isolated blip — one page, one bad moment —
would trip the breaker, and with a 30 second open window that turns one failed page into one
skipped invocation. **It converts a partial failure into a total one**, and there is no evidence
yet about how PNCP actually fails: whether its bad moments are isolated or wholesale. The
observation above is a single data point of the wholesale kind.

Across invocations the breaker already works well, which is the case that matters most once a
scheduler is driving this: the client is a singleton, so a run a minute after a failure finds the
circuit open and fails immediately instead of repeating the discovery.

Revisit when there is production evidence about the shape of PNCP's failures. Until then this is a
decision on record rather than an untouched default.

## Scoring — the domain

| Setting | Value | Label | Basis |
| --- | --- | --- | --- |
| weights | `keyword 30 · segment 30 · value 15 · state 15 · deadline 10` | **Product decision** | Chosen deliberately, reasoning in [ADR 0005](adr/0005-scoring-weights-disqualification-and-time.md). Not derived from data, because there is none yet. |
| deadline horizon, comfortable | `14 days` | **Guess, weakly corroborated** | The samples show time from publication to close of 10, 13, 14, 20 and 44 days, median 14. So a notice seen on its publication day typically has about 14 days left and scores full timing strength, decaying as it ages. Coherent — but that corroboration is five records and was found *after* the number was picked. |
| deadline horizon, viable | `2 days` | **Guess, and a product guess** | No data on how long a small supplier actually needs to assemble certificates. |
| deadline floor strength | `0.10` | **Guess** | Above zero on purpose, so it is not a second disqualification. The specific value is taste. |
| default minimum score | `50` | **Product decision** | Interacts with the weights: 40 is the highest score reachable with no capability signal, so a 50 threshold makes a capability signal mandatory. |

## The honest summary

Of the numbers above, **one is properly evidenced** (`page-size`, and only as "valid"), **one has an
evidenced base with an estimated multiplier** (`max-total-pages`), **one is corroborated after the
fact** (the 14-day horizon), and **everything else is convention or product judgement.**

**The resilience configuration in particular is entirely convention. The first real outage will be
the first evidence any of it has ever had.** That is normal for a system with no production
traffic, and it is worth knowing rather than discovering.

The `Feedback` aggregate exists so that the scoring numbers can eventually be argued with from
evidence rather than from taste.
