---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Fetch PNCP pages with virtual threads and structured concurrency, not reactive streams

## Context and Problem Statement

One week of one state for one modality is 170 pages. The page count is only knowable after the
first response, the work is entirely IO bound, and it runs on a t3.micro with a 256 MB heap. The
pages must be fetched concurrently, bounded, against a public API run by a public body, and a
failure must not leave the rest of the fan out running.

## Considered Options

- **`WebClient` and reactive streams.** `Flux.fromIterable(pages).flatMap(this::fetch, concurrency)`
  is one line, has a concurrency argument built in, and cancels its siblings on error.
- **Virtual threads with `StructuredTaskScope`** and a semaphore.
- **A platform thread pool** with `ExecutorService` and `invokeAll`.

## Decision Outcome

Chosen option: **virtual threads with `StructuredTaskScope`**.

The honest summary of the trade is that reactive would have been fewer lines and the rejected
option is not a bad one.

What decided it is that the rest of this codebase is blocking, synchronous and ordinary. The mapper
returns a sealed result, the domain is plain Java, and a `Flux` at this boundary would have meant
either carrying reactive types up through the ingestion pipeline or collapsing them back with
`.block()` at the first opportunity — which is the reactive style paying its costs and collecting
none of its benefits. Virtual threads deliver the same IO concurrency while every stack trace stays
readable and every failure is caught where it happened.

The platform thread pool was rejected for the opposite reason: it needs a pool sized by hand, and
`invokeAll` gives no sibling cancellation. Building that by hand is building
`StructuredTaskScope` badly.

### What the scope gives, and what it does not

**It gives cancellation and termination.** The first job to throw shuts the scope down; there is no
point hammering a public API for pages whose results are about to be discarded. Closing the scope
interrupts every unfinished subtask and *then waits for all of them to terminate*, so an exception
cannot propagate while threads are still running. A bare executor promises neither, and a test
asserts this by observable termination — every subtask registers on entry and in a `finally`, and
the terminated set must equal the started set — rather than by trusting the javadoc.

**It does not give a concurrency ceiling.** Forking a thousand subtasks forks a thousand virtual
threads and they would all reach the network at once. The ceiling is a `Semaphore`, acquired inside
each subtask so a waiting job holds nothing but a parked thread. Its test measures the maximum
observed overlap, and also asserts that overlap above one occurred, because a ceiling assertion
over work that never contended would pass while proving nothing.

## The preview API, stated plainly

`StructuredTaskScope` is a **preview API in Java 21** (JEP 453). It requires `--enable-preview` at
compile time and at run time.

### It was three places. It was four.

This document originally said the flag lived in three places: the compiler plugin, both test JVMs,
and `JAVA_TOOL_OPTIONS` in the Dockerfile. That was wrong in a way that only shows up at runtime.

`docker-compose.yml` also sets `JAVA_TOOL_OPTIONS` for that service, to pin the heap. **Compose
replaces an image environment variable rather than appending to it**, so the compose value won and
the flag disappeared. The container logged

```
Picked up JAVA_TOOL_OPTIONS: -Xmx256m
```

and the service failed to start, surfacing as an `UnsupportedClassVersionError` wrapped in a
`BeanCreationException` while the bean graph was being built — thirty frames deep, naming a class
nobody was thinking about, for one missing word on a command line. Every test passed. The build was
green. This is the cost of the preview API, and it is not theoretical.

### Where the flag lives now, and why there

**On the Dockerfile `ENTRYPOINT`**, not in any environment variable:

```dockerfile
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/radar-ingestion.jar"]
```

The reasoning is about who can reach it. `--enable-preview` is **not a tunable**: the image cannot
run without it. An environment variable can be replaced wholesale by every layer above the image —
compose did, Kubernetes will, `docker run -e` would — and each of those replacements is silent. The
entrypoint is the only place none of them can clobber by accident. The heap stays in
`JAVA_TOOL_OPTIONS`, where an orchestrator overriding it is somebody's intent rather than a silent
amputation.

So: **three places, and none of them an environment variable.** The compiler plugin, both test JVMs,
and the entrypoint.

### Two guards, because a comment was not enough

The original mistake was documented in a comment in the POM, and the comment was wrong. Comments do
not fail builds.

- **`PreviewFlagWiringTest`** asserts at build time that the flag is on the `ENTRYPOINT`, that it is
  *not* in `ENV JAVA_TOOL_OPTIONS`, that compose does not override the entrypoint, and that the POM
  carries it for the compiler and both test JVMs. Reintroducing the original bug makes two of these
  fail.
- **`PreviewFeatures.requireEnabled()`** runs before `SpringApplication.run` and loads the one
  preview-compiled class deliberately. If the flag is missing it throws one sentence naming the
  flag, the three places, and this document — instead of a class-loading error inside a bean graph.
  It tests the condition rather than a proxy for it: reading `getInputArguments` would only tell you
  what was passed, not whether it worked.

**The API finalises in Java 25**, and it does not finalise in the shape Java 21 has. The Java 21
form, `new StructuredTaskScope.ShutdownOnFailure()` with `throwIfFailed`, is replaced by
`StructuredTaskScope.open(...)`. **A JDK upgrade past 21 is therefore a rewrite of that class, not a
recompile.** That is a real cost and it is accepted with open eyes.

The mitigation is containment: `StructuredFanOut` is the only class in the project that touches the
API, behind an ordinary method taking a list and a function. Everything else — the source, the page
client, the mapper — is unaware. The rewrite is one file and its tests already exist.

A note on the enforcer rule, so nobody credits it with more than it deserves: `requireJavaVersion
[21,22)` from stage 01 happens to pin exactly the JDK that preview classfiles require. That is a
**happy accident**. It was written to keep the build on an LTS, not to guard preview code, and it
would not have caught the problem it now incidentally prevents.

## The fan out cap, and how its first value was wrong

The cap is on **total pages per invocation**, currently 500, and it **throws rather than
truncating**. Partial data that looks complete is the failure this project keeps designing against.
Both phases check it before submitting anything, so the cap never fires with work in flight.

500 is roughly three times an estimated daily all-states run of 100 to 160 pages. **1000 was
considered and rejected**: the extra headroom would only ever serve a multi-day catch-up after an
outage, and *a multi-day catch-up belongs in chunking the window, not in loosening the guard.* A cap
that forces an operator to narrow a backfill is doing its job.

### The sizing error

The first value was 200, and the reasoning behind it was wrong in a way worth recording.

It was justified by "the recorded week-long SP window returned `totalRegistros: 2`". That figure
came from `contratacoes/proposta`, the endpoint that returns only notices whose proposals are still
open. **The poller uses `contratacoes/publicacao`, whose envelope for the same window reads
`totalRegistros: 1697, totalPaginas: 170`.** The estimate was out by roughly three orders of
magnitude, and 200 pages turned out to be about one week of one state rather than the enormous
headroom it was claimed to be.

This is the same shape as the defect recorded in
[ADR 0007](0007-canonical-content-hash-for-change-detection.md): a number that looked evidenced and
was not. In both cases the figure was real, it was simply measured from the wrong thing, and
nothing about it looked wrong when read back.

### Consequences

- Good, because the fetch is bounded twice: in flight by the semaphore, in total by the cap.
- Good, because every stack trace is a real stack trace and every failure keeps its classification.
- Bad, because a JDK upgrade past 21 requires rewriting `StructuredFanOut`.
- Bad, because `--enable-preview` in production is unusual, and a reader who does not know why will
  assume carelessness. That is what this document is for.
- Bad, and demonstrated rather than predicted: the flag has to be right in three places, a green
  build proves nothing about two of them, and getting it wrong fails at startup with a stack trace
  that points nowhere near the cause. Two guards now cover that, and neither existed until the
  mistake had already been made in a running container.
