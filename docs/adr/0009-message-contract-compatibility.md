---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Version message contracts explicitly, and say what a consumer does with a version it does not know

## Context and Problem Statement

A queue decouples two services in time as well as in space. A message written by one deployment is
read by another, possibly days later: SQS retention outlives any deploy window, so **a consumer will
eventually read a message written by a producer it does not match.** That is not an edge case, it is
the normal condition during every rolling deployment.

The failure this invites is silent. A consumer that reads a message it half-understands writes
half-correct data, and nothing reports an error.

## Considered Options

- **No version field**, inferring the shape from which fields are present.
- **An explicit `schemaVersion` on every message.**
- **A schema registry**, with contracts published and validated centrally.

## Decision Outcome

Chosen option: **an explicit `schemaVersion`**, an `int`, on every message, starting at 1.

Inferring the shape from present fields was rejected because it cannot express a change of
*meaning*. If version 2 keeps every field of version 1 but changes what one of them means — a value
in cents rather than reais, a timestamp in a different zone — no amount of looking at the fields
reveals it, and that is exactly the change that corrupts data quietly.

A schema registry was rejected as disproportionate. It is another service to run inside a free-tier
budget, for two contracts between two services in one repository, where both sides are compiled
against the same `radar-shared` artifact.

## The policy

### Reading a version you do not know

**Higher than any version this consumer understands → the message goes to the DLQ, with a named
reason.**

Not skipped, because skipping is silent data loss: the notice was discovered, it was never
persisted, and nobody is told. Not best-effort parsed, because a higher version may have changed
what a field means, and a plausible-looking wrong value is worse than a stop.

The DLQ is the right destination because it is already the place a human looks, and because the
message stays intact for reprocessing once the consumer catches up.

#### It redrives naturally; it is not moved deliberately

The consumer throws, and the message reaches the DLQ the ordinary way, after `maxReceiveCount`
receives. It is **not** deleted from the main queue and re-sent to the DLQ by hand.

This is noisier — three receives and three log lines for something that will never succeed on this
deployment — and the noise is worth paying for two reasons.

**A deliberate move has no transaction.** Sending to the DLQ and deleting from the main queue are
two API calls with no atomicity between them. Fail after the send and the message is duplicated;
fail after the delete and it is lost. Redrive is one mechanism, implemented by SQS, already covered
by the poison-message test; a manual move would be a second path to the same outcome with its own
failure modes.

**Redrive gives a free recovery window that a deliberate move would destroy.** A version-too-new
message is almost always a deployment ordering problem: the producer rolled out before the consumer.
If the consumer is upgraded within `visibility timeout x maxReceiveCount`, a later receive
**succeeds and the message never reaches the DLQ at all.** Moving it immediately would throw that
away and turn a self-healing situation into manual work every single deploy.

The noise is bounded — three lines, once, not a loop — and each line carries the message id, both
version numbers and the receive count, so the three read as one story rather than as three identical
alarms.

**Lower than the current version → must still be readable.** A consumer keeps readers for every
version it has ever supported. Dropping an old reader is itself a breaking change, and it is only
safe once no message of that version can still exist: **at minimum the queue's retention period plus
the longest plausible deployment window.** With 14-day retention, a reader removed less than a
fortnight after its version stopped being written will lose messages.

**Unknown fields inside a known version → ignored.** That is what makes additive change cheap.

### What counts as breaking

A version bump is required for:

- removing a field;
- changing a field's type;
- **changing a field's meaning, units, or time zone**, even with the type unchanged;
- making an optional field required;
- narrowing the accepted values of a field.

A version bump is **not** required for:

- adding an optional field;
- relaxing validation;
- documentation.

The asymmetry is deliberate. Additive changes are free precisely because consumers ignore unknown
fields; everything else is a new version, because everything else can be misread.

### Why `sourceUpdatedAt` is a String, and what shape it must be

The contract holds the timestamp as ISO-8601 text rather than as `java.time.Instant`. An `Instant`
serialises as `"2026-09-01T17:22:01Z"` or as `1756747321.000000000` depending on a Jackson feature
flag, which would make the wire format depend on serializer configuration that neither side
declares. **A versioned contract cannot have a shape that a configuration change can alter.** The
decision is made once, here, in the type.

But a string contract with an unpinned format is worse than a typed one, because it drifts silently.
"The adapter parses it" only holds if the adapter knows exactly what shape to expect, and a format
that lives only in prose drifts the first time somebody writes a producer without reading the prose.

**The format is therefore enforced, not documented.** A value must be exactly what
`Instant.toString()` produces for the instant it denotes — the record's constructor parses it and
requires it to round trip unchanged. Parsing alone would not be enough: it accepts
`2026-09-01T14:22:01-03:00`, which is the same moment written differently, and two spellings of one
value is precisely how a contract starts to drift.

| Value | Accepted | Why |
| --- | --- | --- |
| `2026-09-01T17:22:01Z` | yes | canonical |
| `2026-09-01T17:22:01.500Z` | yes | canonical; Java renders fractions in groups of three |
| `2026-09-01T17:22:01` | no | naive — and this is exactly the shape PNCP publishes, which this system converts at the adapter boundary |
| `1756747321` | no | epoch, whatever a serializer might prefer |
| `2026-09-01T14:22:01-03:00` | no | same instant, second spelling |
| `2026-09-01T17:22:01.000Z` | no | padded; renders without a fraction |
| `2026-09-01T17:22:01.5Z` | no | one fractional digit; renders as `.500Z` |

A message with a bad timestamp cannot be constructed, so it cannot be published. That is stronger
than a producer-side test, which only covers the producers somebody remembered to test.

`null` means PNCP published no `dataAtualizacaoGlobal`, which is legal. A present but blank value is
a producer bug and is rejected at construction rather than treated as absence.

### Consequences

- Good, because a mismatched consumer stops loudly at a known place instead of writing half-correct
  rows.
- Good, because additive evolution needs no coordination between deployments.
- Good, because `radar-shared` stays records-only: the policy is enforced by the consumer, not by
  annotations or a framework on the contract.
- Bad, because consumers accumulate readers for old versions, and the rule for deleting one depends
  on queue retention rather than on anything visible in the code. It belongs in the runbook, not
  only here.
- Bad, because a version-too-new message in the DLQ looks identical to a genuine poison message. The
  reason recorded with it is the only thing that distinguishes them, which is why it is named rather
  than generic.
