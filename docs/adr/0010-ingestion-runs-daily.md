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

Volume is measured, not assumed. `contratacoes/publicacao` reported `totalRegistros: 1697,
totalPaginas: 170` for one week of SP, and a live run reported 190 pages for eight days — about
**240 SP notices a day, or 24 pages** at `page-size: 10`. The national multiplier of roughly four to
six is an estimate from SP's share of Brazilian municipalities.

| Schedule | Runs per day | Pages fetched per day |
| --- | --- | --- |
| **Daily** | 1 | **200–320** |
| Every 6 hours | 4 | 800–1,280 |
| Hourly | 24 | 4,800–7,680 |

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

## The coupling this creates

Two numbers are now joined and **neither can move alone**: `radar.discovery.lookback-days` and
`radar.pncp.max-total-pages`.

The lookback overlaps the schedule deliberately, so a missed run does not lose a day permanently.
That overlap is free on the storage side, because the consumer deduplicates on content hash, and it
is **not** free on the request side: the redundant day is a full day of pages on every run, 100 to
160 of them, which is 100 percent overhead on the minimum.

At the defaults — 2 days, a pessimistic 160 pages a day — a run needs about **320 pages against a
cap of 500**. The margin is thinner than it looks: a 1.5× safety assertion clears at **480 against
500**. Raising the lookback to 3, or PNCP volume growing by half, breaks it.

**A lookback wide enough to exceed the cap would throw on every run and never once succeed** — a
configuration deadlock, and one that a scheduled job would surface at an unhelpful hour.
`DiscoveryWindowFitsTheFanOutCapTest` binds the real configuration and fails the build instead,
with the arithmetic in the message:

```
a 4 day lookback needs about 640 pages at 160 a day, against a cap of 500
```

That test is the thing stopping somebody from raising one number and discovering the other in
production. It is not documentation of the coupling; it is the enforcement of it.

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
