---
status: accepted
date: 2026-09-21
decision-makers: Eduardo Furlanetto
---

# Record architecture decisions

## Context and Problem Statement

radar-pncp is built under tight, non-obvious constraints: two JVMs on a single t3.micro with
1 GB of RAM, everything inside the AWS free tier, a domain module that must stay framework free.
Decisions taken under constraints like these look arbitrary six months later, and the reasoning
that produced them is exactly what a reader of a public portfolio repository wants to see.

How do we keep a durable record of why the system looks the way it does?

## Considered Options

- **Architecture Decision Records in the repository, MADR format**
- **A wiki or an external document** (Notion, Confluence, Google Docs)
- **No record at all**, relying on commit messages and code comments

## Decision Outcome

Chosen option: **Architecture Decision Records in the repository, MADR format**, because the
record lives and versions with the code it explains, is reviewable in the same pull request as
the change it justifies, and stays readable without an account on a third-party service.

### Consequences

- Good, because a decision and its implementation are reviewed together, in one pull request.
- Good, because the history of a decision is `git log` on a file, not a page revision list.
- Good, because MADR is plain Markdown: no tooling, no export step, renders on GitHub.
- Bad, because writing an ADR is friction at exactly the moment the author wants to move on.
  We accept that friction; it is the cost of the record.

### Rules

- ADRs live in `docs/adr`, named `NNNN-title-in-kebab-case.md`, numbered sequentially.
- Every architectural decision gets one: anything that changes the module boundaries, the AWS
  footprint, the cost profile, the persistence model, or a cross-cutting constraint.
- An ADR is never edited to reverse its meaning. A superseded ADR keeps its text and gains a
  `superseded by` status pointing at the ADR that replaced it.
- Status is one of `proposed`, `accepted`, `rejected`, `deprecated`, `superseded by NNNN`.
- ADRs are written in English, like everything else in this repository.

## More Information

MADR: <https://adr.github.io/madr/>
