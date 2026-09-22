---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Classify procurements into a closed segment vocabulary, not into inferred CNAE codes

## Context and Problem Statement

A company knows its own line of business: it declares a CNAE, and a search profile lists the CNAEs
it wants work in. The procurement side has nothing to compare that against.

**PNCP publishes no classification of what is being bought.** A case insensitive search for `cnae`
across every recorded sample in `docs/samples` returns zero hits. The item level payload does carry
fields where a classification would plausibly live, `ncmNbsCodigo`, `catalogo`,
`categoriaItemCatalogo` and `catalogoCodigoItem`, and all of them are null in the sample. The only
description of what is being bought is free text: `objetoCompra`, plus an often empty
`informacaoComplementar`.

So a capability rule has to derive the procurement side from that free text. The question is what
it should derive.

## Considered Options

- **Ask the model for CNAE codes** and match them against the profile CNAEs directly.
- **Ask the model for a value from a closed vocabulary** defined in the domain, and map the company
  CNAEs onto the same vocabulary.
- **Drop the capability rule entirely** and match on keywords, geography, budget and timing.

## Decision Outcome

Chosen option: **a closed vocabulary**, `ProcurementSegment`, with fifteen values.

A CNAE is a seven digit code inside a hierarchy of more than a thousand of them. A language model
asked for one will produce a well formed, plausible, confidently wrong code as readily as a right
one, and nothing downstream can tell the difference: every seven digit code looks exactly like
every other. The failure is silent and it is invisible in exactly the way that matters, because a
wrong code does not produce an error, it produces a wrong match or a missing one.

Fifteen enum values can be listed in a prompt, validated on the way back, and argued about by a
human. A value outside the vocabulary cannot be represented at all.

Dropping the rule was rejected because capability is the thing that decides whether a company can
deliver at all. Without it the system ranks on geography and budget, which is a filter, not a
recommendation.

### The trade it buys

**Accuracy up, resolution down.** This is the whole trade and it should be stated plainly.

A CNAE distinguishes retail sale of computers from computer maintenance from custom software
development. `IT_SERVICES` and `IT_HARDWARE` do not. A company whose business is narrower than its
segment will see matches inside that segment it cannot serve, and the keyword rule, not this one,
is what separates them.

In exchange, every value this rule ever sees is a value the domain defined. There is no class of
error where the model invents a code, and no silent drift as the model changes between versions.

### The CNAE side of the map

`CnaeSegmentMap` maps at division level, the first two digits, and answers `OTHER` for every
division it does not claim. It is deliberately small, currently 28 divisions.

A wrong entry in that map is worse than a missing one. A missing division falls back to `OTHER` and
the rule stays silent, which costs a match. A wrong division silently attaches a company to work it
does not do, and nobody sees the mapping that caused it. Coverage will be extended when a real
company declares a CNAE the map does not know, not by guessing at the table in advance.

### Consequences

- Good, because a classification outside the vocabulary is unrepresentable rather than merely
  unlikely.
- Good, because the rule contributes at the confidence of the inference rather than at full
  strength, so an uncertain classification weighs less without needing a threshold.
- Good, because before enrichment arrives the rule reports itself not applicable, which the match
  breakdown surfaces. An unenriched procurement is visibly scored on 70 percent of the criteria
  rather than quietly penalised.
- Bad, because fifteen buckets are coarse, and two companies in genuinely different businesses can
  share a segment.
- Bad, because the vocabulary is now a compatibility surface. Adding a value changes what the model
  is asked for and what every stored enrichment means, so it is a decision, not a refactor.
