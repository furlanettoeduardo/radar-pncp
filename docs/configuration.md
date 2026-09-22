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
| `page-size` | `10` | **Weakly evidenced** | `tamanhoPagina=10` appears in all five recorded request URLs. That is evidence 10 *works*, not that it is the maximum or the best choice — it was copied. No evidence exists about the ceiling, and this is the single largest lever on total request volume. |
| `max-total-pages` | `500` | **Evidenced base, estimated multiplier** | The base is real: `contratacoes/publicacao` reports `totalPaginas: 170` for one week of SP at modality 6. The national daily figure of 100–160 pages is an extrapolation from SP's share of Brazilian municipalities, not a measurement. 500 is roughly three times that. |
| `modality-codes` | `[6]` | **Evidenced, and a known limitation** | 6 is the only `codigoModalidadeContratacao` any sample uses. PNCP rejects the call without one. The system therefore sees a fraction of PNCP's catalogue, deliberately, rather than inventing the rest of the table. |
| `max-concurrent-requests` | `8` | **Guess** | No PNCP rate-limit documentation was consulted and nothing in the samples speaks to it. 8 is a conventional polite number. |
| `connect-timeout` | `2s` | **Guess** | No latency measurement. Convention. |
| `read-timeout` | `10s` | **Guess** | Same. The sample headers carry `fetched-at` but no duration, so no observed PNCP latency exists anywhere in this repository. See the observation below — it is deliberately *not* evidence for this value. |
| `operation-deadline` | `5m` | **Guess, with arithmetic behind it** | A healthy run at the cap is about 500 pages over 8 at a time, roughly a minute; this leaves five times that. The number it is protecting against is real: 500 pages × 3 attempts × a 10s read timeout is over half an hour for one invocation. |
| `--enable-preview` | on the Dockerfile `ENTRYPOINT` | **Not a tunable** | `StructuredTaskScope` is a preview API in Java 21 and the image cannot run without the flag. Deliberately *not* in `JAVA_TOOL_OPTIONS`: docker compose sets that variable and replaced it wholesale, which stripped the flag and broke startup once. An entrypoint is the one place no orchestrator clobbers by accident. Guarded by `PreviewFlagWiringTest` at build time and `PreviewFeatures.requireEnabled()` at boot. See [ADR 0008](adr/0008-virtual-threads-and-structured-concurrency-for-page-fetching.md). |
| `user-agent` | project + repo URL | n/a | Not a tunable. PNCP is run by a public body and being identifiable costs nothing. |

### Observation, 2026-09-22: PNCP returned 504 under load

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
| `lookback-days` | `2` | **Guess, with a measured cost** | The value is a judgement about how much outage to survive. What it costs is measured, below. |
| `states` | `[]` (everywhere) | n/a | Empty is what `ProcurementQuery` already expresses and what PNCP accepts as an omitted `uf`. |

### What the lookback overlap actually costs

An earlier note in this repository said re-reading a day "costs nothing, because the consumer
deduplicates on content hash". **That is true for storage and false for requests**, and the
difference matters because PNCP is a public API that has already been observed falling over.

Measured volume, from two independent readings:

- `contratacoes/publicacao` reported `totalRegistros: 1697, totalPaginas: 170` for one week of SP;
- a live run reported `totalRegistros: 1891, totalPaginas: 190` for eight days of SP.

Both give **about 240 SP notices a day, or 24 pages** at `page-size: 10`. The national multiplier of
roughly 4 to 6 is an estimate from SP's share of Brazilian municipalities, so:

| | pages per run |
| --- | --- |
| One day, nationally | 100–160 |
| **Two days (`lookback-days: 2`)** | **200–320** |
| The overlap alone, per run | **100–160** |

So the overlap is **100 percent overhead on the minimum**, not a handful of pages. It is still worth
paying: a single missed run without it loses a day of notices permanently, and a lost notice is the
one failure this system exists to prevent. But it is a real, recurring cost against somebody else's
infrastructure and it should be stated as one.

### Why the schedule should be daily, and what sub-daily would cost

**PNCP's query granularity is a calendar date.** `dataInicial` and `dataFinal` are `yyyyMMdd`, so the
narrowest window obtainable is one whole day. A run at any interval therefore fetches at least a
full day of pages, and running more often than daily re-fetches the same pages:

| Schedule | Runs per day | Pages per day |
| --- | --- | --- |
| **Daily** | 1 | **200–320** |
| Every 6 hours | 4 | 800–1,280 |
| Hourly | 24 | 4,800–7,680 |

Sub-daily scheduling multiplies request cost linearly and buys at most one day of freshness, against
a median proposal window of 14 days. Daily is the interval the API's own granularity argues for.

### The window and the fan-out cap are checked against each other

A lookback wide enough to need more pages than `max-total-pages` would throw on **every** run and
never once succeed — a configuration deadlock. Two things close it:

- At runtime, `PncpFanOutTooLargeException` already says to narrow the date range rather than raise
  the cap, so the failure is at least legible.
- At build time, `DiscoveryWindowFitsTheFanOutCapTest` binds the real configuration and fails if
  `lookback-days x 160` exceeds the cap, or comes within a 1.5x margin of it. Setting
  `lookback-days: 4` fails it with *"a 4 day lookback needs about 640 pages at 160 a day, against a
  cap of 500"*.

**The current margin is thinner than it looks.** At the pessimistic estimate the defaults need 320
pages of a 500 cap, so the 1.5x margin assertion clears at 480 against 500. Raising `lookback-days`
to 3, or PNCP volume growing by half, breaks it. That is the intended behaviour — it should break in
CI rather than at 3am — but it means this pair of numbers is close-coupled and neither moves alone.

## Resilience — hardcoded in `PncpPageClient`

| Setting | Value | Label |
| --- | --- | --- |
| retry attempts | `3` | **Guess** — convention |
| retry backoff | `200ms`, ×2, jitter `0.5` | **Guess** — convention, not tuned against any observed PNCP recovery |
| breaker sliding window | `20` calls | **Guess** |
| breaker minimum calls | `10` | **Guess** |
| breaker failure threshold | `50%` | **Guess** |
| breaker open duration | `30s` | **Guess** |

These four breaker values are smaller than Resilience4j's defaults (100 / 100 / 50% / 60s) because
per-invocation call volume here is low. That reasoning is sound and the numbers are still taste.

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
