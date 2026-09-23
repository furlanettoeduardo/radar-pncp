---
status: accepted
date: 2026-09-23
decision-makers: Eduardo Furlanetto
---

# A transient failure extends a message's visibility; poison is left to the redrive policy

## Context and Problem Statement

The consumer stores discovered procurements. The queue has a redrive policy: after
`maxReceiveCount: 3` receives, SQS moves the message to a dead letter queue.

**A redrive policy counts receives and cannot see why any of them failed.** A malformed payload and
a database maintenance reboot consume receives identically. At a 30 second visibility timeout, three
receives span **90 seconds**, so any database outage longer than that dead-letters every message in
flight.

Redrive would recover them — the consumer is idempotent, so replaying is a no-op — but **an RDS
single-AZ maintenance reboot is one to three minutes**, and a routine maintenance window should not
need an operator.

## Considered Options

- **Extend the message's visibility on a transient failure**, so the same three receives span an
  outage.
- **Pause polling while the database is unhealthy**, and resume when it recovers.
- **Raise `maxReceiveCount`**, so more receives are available to absorb an outage.

## Decision Outcome

Chosen option: **extend visibility on a transient failure**, classifying at the point of failure.

### Why not pause polling

It needs a component that does not otherwise exist: something to detect the outage, stop the
listener container, and poll for recovery. That component has **a failure mode of its own, and it is
worse than the one it fixes** — a pause that never lifts is a consumer that has silently stopped,
and nothing about the process looks wrong. A visibility extension that is wrong merely redelivers a
message sooner than intended.

It is also not sufficient on its own: messages already received when the pause begins still have to
be dealt with, so the visibility logic is needed either way. Pausing is strictly additional work on
top of the thing that was going to be built.

### Why not raise maxReceiveCount

It buys time at the cost of the thing the count is for. A genuinely poisoned message should reach
the dead letter queue **quickly**, where somebody sees it; raising the count to cover a ten minute
outage would make a poisoned message take ten minutes to become visible. The two failures want
opposite treatment, which is the argument for telling them apart rather than tuning a number that
applies to both.

### The classification, and the trap in it

- **Poison** — deserialisation failure, a payload the mapper rejects, a schema version newer than
  this consumer understands, a constraint violation. Fails fast; the redrive policy takes it in
  about 90 seconds.
- **Transient** — the database is unreachable. The message's visibility is extended and the failure
  is rethrown, so nothing is acknowledged.
- **Anything unrecognised is poison**, deliberately. An unknown failure that dead-letters in ninety
  seconds is a bug somebody notices; the same failure quietly retried for ten minutes is a bug
  nobody notices.

**Spring has a `TransientDataAccessException` hierarchy that reads as though it were exactly this
question, and it is the wrong one.** A lost connection surfaces as
`CannotGetJdbcConnectionException`, which extends `NonTransientDataAccessResourceException`.
Classifying on Spring's own notion of transient would have missed **the single case this decision
exists for**, while still passing a test written against `QueryTimeoutException`.

So the rule is stated the other way round: anything the data access layer raises is the database's
problem and worth waiting out, **except** a constraint violation, which is the payload's fault and
will fail identically forever. `ConsumerFailuresTest` pins the trap itself, asserting that a lost
connection is *not* a Spring transient exception and *is* classified as transient here.

### The numbers

The backoff multiplies the visibility timeout by four on each failed receive. The first wait is the
queue's own visibility timeout, so the first retry happens exactly when SQS would have redelivered
anyway and nothing is slower in the ordinary case.

| Receive | Hidden for | Elapsed since publish |
| --- | --- | --- |
| 1 | 30s | 30s |
| 2 | 2 min | 2 min 30s |
| 3 | 8 min | **10 min 30s** |

**10 minutes 30 seconds against a reboot of one to three minutes**, roughly 3x margin. A multiplier
of two would have spanned three and a half minutes, short of the thing it is meant to survive.

Extending the visibility does **not** consume a receive; it only delays the next one. The message
still gets exactly three.

## Consequences

- Good, because a maintenance window no longer needs an operator, and poison still becomes visible
  in ninety seconds.
- Good, because the decision is made at the one point where the exception type is known, with no
  extra component, no background thread and no global state.
- **Bad, because `maxReceiveCount: 3` no longer means what it appears to.** It is about ten minutes
  for a transient failure and about ninety seconds for poison. This surprises anyone reading the
  queue attributes alone, which is why it is in the runbook next to them.
- **Bad, because `visibility-timeout` now scales the whole backoff.** Moving it from 30s to 60s
  makes the span 21 minutes, not 11. The two numbers are coupled and neither moves alone.
- Bad, because a misclassified poison message takes ten and a half minutes to reach the dead letter
  queue instead of ninety seconds. Accepted: misclassification is our bug, and an operator waiting
  out a maintenance window is routine.
- An outage longer than ten and a half minutes still dead-letters, and that is intended. At that
  point somebody should know, and redrive is safe.

## What would change this answer

- **A managed database with no maintenance reboots**, or Multi-AZ with fast failover, would shrink
  the outage this is sized against. Multi-AZ is not in the free tier, so this stays.
- **Evidence that outages routinely exceed ten minutes** would argue for a scheduled re-drive of the
  dead letter queue rather than a longer backoff, because at that length the queue is the better
  place to wait.
- **Batch consumption**, if message volume ever justified it, would change the shape entirely: a
  batch has one visibility to extend and several messages with different verdicts.
