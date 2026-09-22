---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Separate an input that has not arrived yet from one that never will

## Context and Problem Statement

Stage 02 shipped a single `NotApplicable` outcome for any rule that could not run. Two very
different situations landed in it, and they are indistinguishable in the output:

```
Right trade, local, budget hidden          -> 85, coverage 85%
Right trade, local, right size, unenriched -> 70, coverage 70%
```

A hidden budget is **permanent**. `orcamentoSigiloso` is what that procurement *is*; 85 on 85
percent of the criteria is the final answer and no amount of waiting changes it.

An unenriched procurement is **transient**. No worker has produced its segment yet. Tomorrow the
same procurement scores differently with nothing in the world having changed, and a list ranked by
score reshuffles for reasons its reader cannot see.

## Considered Options

- **Split the outcome type** into a permanently unavailable input and one that has not arrived yet.
- **Refuse to produce a match at all** until a procurement is enriched, so every score is final.
- **Leave it alone** and accept that scores move.

## Decision Outcome

Chosen option: **split the outcome type**. `RuleOutcome.NotApplicable` becomes
`RuleOutcome.Unavailable` and `RuleOutcome.Pending`, and `Match` gains a derived `provisional()`.

### The deciding argument is cost

Refusing to score an unenriched procurement sounds tidier, and it would make the stage 4 and 5
pipeline strictly sequential. It was rejected because **it would force enrichment of 100 percent of
intake.**

Under the chosen design an unenriched procurement still scores up to 70 out of 100 on signals that
cost nothing: keyword, state, estimated value and deadline. That means the day's intake can be
ranked for free, and **LLM calls can be spent only on the procurements already near a threshold.**

That is a cost lever, and cost is a non-negotiable in this project: the system has to stay inside
the AWS free tier, and model calls are the main variable expense in it. Waiting for enrichment
before scoring deletes the lever entirely — every ingested notice would have to be paid for,
including the ones no profile would ever have matched.

### Two further arguments, pointing the same way

**An outage would take the product dark.** `EnrichmentProvider` returns an `Optional` precisely
because an unavailable model is an expected outcome on that path. Gating matches on enrichment
turns that soft dependency into a hard one: no model, no matches, for anybody.

**Worse, the loss would be silent and permanent.** The proposal deadline rule disqualifies. A
notice closing in three days that waits behind a rate limited queue does not arrive late, it
arrives *disqualified*, and it is never shown at all. That is the same silent deletion failure that
[ADR 0005](0005-scoring-weights-disqualification-and-time.md) pinned the time zone conversion to
avoid, reaching the user through a different door.

### Why the reshuffling is acceptable

It converges, and it is visible.

A match id is derived from the procurement and profile pair, so re-scoring is an idempotent upsert
rather than a second row. As enrichment arrives the score rises, the coverage rises with it, and
`provisional()` flips to false. A score that improves as evidence arrives, while saying that it is
still waiting for some, is honest. A score that silently moves is not.

### What it costs

`provisional()` is **derived from the breakdown, not stored**, so it adds no state to persist and
no field to migrate. A consumer that ignores it loses an explanation, not correctness. That keeps
the distinction out of the API surface unless a later stage decides it is worth showing.

Only one of the five places that previously answered `NotApplicable` is genuinely transient: the
segment rule with no enrichment. A profile that declares no keywords, no CNAEs or no states, and a
procurement whose budget PNCP hid, are all `Unavailable` — none of them changes until a human edits
something.

### Consequences

- Good, because the type system now carries the difference between "we measured everything we
  could" and "we have not finished measuring", instead of leaving it to a comment.
- Good, because enrichment stays optional, which keeps it targetable and keeps an outage from
  emptying the product.
- Bad, because the sealed interface has five cases and every exhaustive switch over it grows one
  more arm.
- Bad, because a provisional match can be acted on by a user before the score settles. It can only
  rise, since a pending rule contributes zero until it runs, but a user who dismissed something at
  70 will not come back to it at 95. Surfacing `provisional()` in the interface is how stage 6
  would address that.
