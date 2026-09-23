# radar-pncp

Ingests Brazilian public procurement notices from the [PNCP](https://pncp.gov.br) open API,
enriches them with an LLM, and matches them against company profiles.

Two Spring Boot services, one t3.micro, inside the AWS free tier. The constraint is the point:
every dependency and every AWS service in this repository had to earn its place in 1 GB of RAM.

> **Status: stage 04 complete, ingestion end to end.** Notices are discovered from PNCP, published
> to SQS and stored in PostgreSQL, on a schedule, without losing a day quietly. Discovery is chunked
> by publication date and modality, so one failed page no longer discards a whole run — against an
> API measured failing 36 of 42 calls in an afternoon, that mattered. A date that leaves the lookback
> window uncovered becomes a named, alertable event rather than an absence. The consumer is
> idempotent and tells a poisoned message apart from a database outage, so a maintenance reboot does
> not fill the dead letter queue. Enrichment and the API are next. See
> [docs/architecture.md](docs/architecture.md) for what is built and what is planned,
> [docs/configuration.md](docs/configuration.md) for which of the numbers are evidence,
> [docs/runbook.md](docs/runbook.md) for operating it, and [docs/adr](docs/adr) for why.

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Resilience4j · virtual threads and structured
concurrency · Maven multi-module · Docker Compose · JUnit 5, AssertJ, ArchUnit, Testcontainers,
WireMock

## Modules

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `radar-domain` | Domain model and business rules. Zero framework, enforced by the build. | nothing |
| `radar-shared` | Message contracts between the services. Records only. | nothing |
| `radar-ingestion` | PNCP client, scheduler, SQS traffic, LLM enrichment. Port 8081. | domain, shared |
| `radar-api` | REST and GraphQL, matching queries, auth. Owns the schema. Port 8080. | domain, shared |

Dependencies point inwards only. Adapters know the domain; the domain knows nobody. Two
independent checks enforce it — `maven-enforcer-plugin` on the dependency tree and ArchUnit on
the imports. See [ADR 0002](docs/adr/0002-bom-import-and-enforced-domain-purity.md).

## Getting started

Requirements: JDK 21, Maven 3.9+, Docker.

```bash
# Run the whole stack. Builds both images from source; the first build is slow.
docker compose up

# Build and run unit tests
mvn -q clean verify

# Build and run integration tests as well (Testcontainers, needs Docker)
mvn -q verify -Pintegration

# Format the code. The build fails on unformatted code, so run this before committing.
mvn spotless:apply
```

If a port is already taken on your machine — a local PostgreSQL on 5432 is the usual culprit —
override it without editing the file:

```bash
RADAR_POSTGRES_PORT=55432 docker compose up
```

`RADAR_API_PORT` and `RADAR_INGESTION_PORT` work the same way. Only the published host port
changes; the services still reach PostgreSQL as `postgres:5432` on the compose network.

Once the stack is up:

| Endpoint | Service |
| --- | --- |
| <http://localhost:8080/actuator/health> | radar-api |
| <http://localhost:8080/actuator/info> | radar-api |
| <http://localhost:8081/actuator/health> | radar-ingestion |
| <http://localhost:8081/actuator/info> | radar-ingestion |
| `localhost:5432`, database `radar`, user `radar` | PostgreSQL |

Only `health` and `info` are exposed. Any other actuator endpoint returns 404 by design, and a
test asserts it.

## Database

`radar-api` owns the schema. Flyway migrations live in
`radar-api/src/main/resources/db/migration` and run on startup. No other module writes
migrations.

## Documentation

- [Architecture and current state](docs/architecture.md)
- [Configuration, and which of it is evidence](docs/configuration.md) — every tunable number,
  labelled evidenced, estimated or guessed
- [Architecture decision records](docs/adr)
- [Contributing](CONTRIBUTING.md) — branches, commits, TDD, definition of done
- [How matching works](docs/adr/0005-scoring-weights-disqualification-and-time.md) — weights, disqualification and time
- [PNCP API samples](docs/samples) — recorded responses the client is written against

## License

[MIT](LICENSE)
