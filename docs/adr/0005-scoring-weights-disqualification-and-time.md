---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Scoring weights, disqualification, and how time is handled

## Context and Problem Statement

Five independent rules produce a single score that a user has to trust. Three things had to be
decided together, because each one changes what the others mean: what each rule is worth, what
happens when a rule cannot run, and what happens when a procurement is simply out of time.

## Decision 1: the weights

| Rule | Weight |
| --- | --- |
| Keyword | 30 |
| Segment | 30 |
| Estimated value | 15 |
| State | 15 |
| Proposal deadline | 10 |

**Technical capability determines whether the company can deliver at all; geography and budget only
determine whether it is worth pursuing.** Keyword and segment together are 60, so capability
dominates, and no single rule can clear a 50 threshold alone: a match requires at least two
independent signals.

The bound falls out of construction rather than being clamped afterwards. `WeightingScheme`
requires the weights to sum to exactly 100 and to name every rule, so a score cannot leave 0 to 100
and a rule added without a price fails the build instead of quietly diluting the scale.

A sharper consequence, asserted by a test: **the maximum score with no capability signal at all is
15 + 15 + 10 = 40**. Under a 50 threshold it is impossible to match on geography, budget and timing
alone.

### State is deliberately the lighter of the two filters

State is already a profile criterion, so weighting it heavily double counts: the profile has
already said where it will work, and paying it again for agreeing with itself inflates every local
result regardless of whether the company can do the job.

**If out-of-state noise ever becomes a problem, the correct fix is to make state a filter, not to
raise its weight.** It must not go above 20 without being converted into one.

## Decision 2: a rule that cannot run scores zero, and says so

An estimated value that PNCP hid under `orcamentoSigiloso`, or a segment for a procurement that has
not been enriched yet, is not a criterion the supplier failed. It is a criterion nobody could
evaluate.

Those rules contribute zero rather than being renormalised away. Renormalising would let a
procurement score highly on thin evidence: two rules out of five, both firing, would read as a
perfect match.

Instead the match carries `evidenceCoverage`, the share of total weight that was actually
evaluable. Because the weights sum to 100, this is simply the sum of the weights of the rules that
ran. A procurement with a secret budget reads as *85, on 85 percent of the criteria* rather than
carrying an invisible 15 point penalty, and the not applicable reasons stay in the breakdown so a
user can see which criteria were missing.

## Decision 3: a closed deadline disqualifies, and time is pinned at the boundary

A procurement whose proposal window has closed is not a weak match. It is not a match. The engine
returns `ScoringResult.Disqualified` and computes no score at all, so the outcome is unreachable by
any combination of weights. A very low score would have invited somebody to tune their way past it.

While the window is open the rule still ranks, because time is a real constraint rather than a flag.
Strength decays from full beyond fourteen days to a floor of 0.1 inside two days, linearly between,
with all three thresholds carried in a `DeadlineHorizon` record rather than as constants in the
rule. Linear because the resulting number can be explained to a user and checked by them: eight days
left, 55 percent of the timing weight.

### Time zone

**PNCP publishes naive local timestamps.** `dataEncerramentoProposta` arrives as
`2026-09-28T09:00:00`, with no offset. Those timestamps are interpreted as `America/Sao_Paulo`, and
the conversion happens at the adapter boundary. **The domain only ever sees `Instant`.**

This matters more than it looks. The deadline rule is a disqualifier, so an hour of drift in that
conversion does not skew a score, it **deletes matches silently**: a procurement that is open
becomes one that is closed, and it disappears with no error anywhere. That is why the conversion is
pinned to a named zone rather than inferred from the host, and why it is tested at the adapter
rather than trusted.

For the same reason the engine never reads the clock. The evaluation instant is a parameter, which
is what makes a match reproducible and an assertion about it honest.

## Consequences

- Good, because tuning weights is editing data, and no rule contains a number.
- Good, because the three ways of scoring nothing are distinguishable: the rule found nothing, the
  rule could not run, or the procurement is disqualified.
- Bad, because `evidenceCoverage` is a second number a user interface has to explain. A single
  score would be simpler and would lie.
- Bad, because the weights are a product judgement with no data behind them yet. The feedback
  aggregate exists so that they can eventually be argued with from evidence rather than taste.
