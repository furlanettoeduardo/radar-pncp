---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Hash PNCP payloads canonically, and normalise money the same way

## Context and Problem Statement

Ingestion needs to answer one question cheaply and correctly: has this notice changed since we last
saw it? The answer drives deduplication, cache keys, and whether a notice is re-enriched at the cost
of a model call.

PNCP publishes `dataAtualizacaoGlobal`, which is cheap but is PNCP's claim rather than an
observation. Something stronger is needed as the authority.

## Considered Options

- **Hash the response bytes as received.**
- **Hash a canonical rendering of the parsed JSON.**
- **Trust `dataAtualizacaoGlobal` alone.**

## Decision Outcome

Chosen option: **SHA-256 over a canonical rendering**, with `dataAtualizacaoGlobal` kept alongside
as the cheap detector that can avoid computing the authoritative one.

Hashing raw bytes is simpler and wrong. PNCP reformatting its output, or emitting the same fields in
a different order, would present every notice in the catalogue as modified and reprocess all of it —
the precise, expensive failure the hash exists to prevent.

The canonical form is:

- object keys sorted, recursively;
- **array order preserved**, because order in an array is content;
- insignificant whitespace removed;
- **null valued fields dropped**, so an explicit null and an absent field hash the same;
- **numbers normalised** through `BigDecimal.stripTrailingZeros().toPlainString()`.

Keys sort by `String.compareTo`, a total order over UTF-16 code units: deterministic,
locale independent, identical on every JVM. A `Collator` would have been locale dependent and would
have made the hash depend on the machine that computed it.

Dropping nulls is not a stylistic preference. The mapper already treats `valorTotalEstimado: null`
and an absent `valorTotalEstimado` as the same thing, because both mean a sigiloso budget. A change
detector that disagrees with the reader about what is the same is worse than no change detector.

## The defect this ADR was written after

The first implementation was wrong, and it is worth recording what it did.

It rendered numbers with Jackson's `asText()`. Jackson parsed `10000` to an `IntNode` and `10000.00`
to a `DoubleNode`, so those became `"10000"` and `"10000.0"` and **hashed differently**. `1e4`
became `"10000.0"`, so it matched one of them and not the other. Explicit nulls were kept, so
`{"a":null}` and `{}` also differed.

Any of those is a formatting difference, not a content difference. The first time PNCP changed how
it serialised a decimal, the entire corpus would have been reprocessed — and it would have looked
like a surge of genuine updates rather than like a bug.

It was found by writing a test for a property that had until then been assumed rather than checked.
The test went red on the first run. Nothing about the implementation looked wrong when read; it
looked like it normalised numbers, because `asText()` reads as though it would.

## Consequence: money is normalised in the domain too

Chasing the numeric half of that defect surfaced a second one, in `radar-domain`.

`MonetaryValue` is a record wrapping a `BigDecimal`. A record's generated `equals` delegates to
`BigDecimal.equals`, which compares **scale as well as magnitude**. So `MonetaryValue.of("10000")`
and `MonetaryValue.of("10000.00")` were unequal, hashed differently, deduplicated as two values and
keyed a map twice — while `compareTo`, written by hand, said they were the same. An implementation
whose `compareTo` disagrees with its `equals` is one `Comparable` explicitly asks not to exist.

`ValueRange.contains` used `compareTo`, so the estimated value rule was never wrong. That is luck,
not design: the defect was one `Set` or one map key away from being visible, and PNCP writes the
same amount both ways.

The amount is now normalised at construction, `stripTrailingZeros` followed by a plain scale for
whole numbers so that a reason string shows a user `10000` and never `1E+4`.

No part of this path uses `double`, here or in the adapter that reads the payload. Floats are parsed
as `BigDecimal` through one shared reader, because reading a monetary value through a double loses
the literal PNCP sent before anything downstream can normalise it.

### One hash is not two hashes

This hash is a **version token**: it answers "has this notice changed in any way", so it covers the
whole payload including `dataAtualizacaoGlobal`. That is exactly right for deciding whether to store
a row, and exactly wrong for the enrichment cache stage 5 will need.

An LLM extraction depends only on the notice **text** — the object, and whatever else is fed to the
model. Keying that cache on this hash would invalidate it whenever PNCP touched a metadata field
that the model never saw, and spend quota re-extracting an identical text. Model calls are the main
variable cost in this system, so that is not a small waste.

**The extraction cache key will therefore be a separate hash, over the notice text only.** Recorded
here rather than in stage 5 because the mistake is easy to make once this hash already exists and
looks reusable.

### Consequences

- Good, because reformatting upstream costs nothing and a real change is still detected.
- Good, because the hash and the mapper now agree about what "the same notice" means.
- Bad, because canonicalising costs a tree walk per notice. At the observed volumes, single digit
  notices per state per day, that is not a number worth optimising.
- Bad, because the canonical form is now a compatibility surface. Changing any rule above
  invalidates every stored hash and reprocesses the corpus once, which is exactly the event this
  design exists to make rare. It is a migration, not a refactor.
