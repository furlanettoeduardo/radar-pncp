# radar-pncp

Ingests Brazilian public procurement notices from the [PNCP](https://pncp.gov.br) open API,
enriches them with an LLM, and matches them against company profiles.

Two Spring Boot services, one t3.micro, inside the AWS free tier. The constraint is the point:
every dependency and every AWS service in this repository had to earn its place in 1 GB of RAM.

> **Status: stage 02 complete, the domain model.** The build, the module boundaries, the domain
> model, the scoring engine and the ports exist and are tested. No adapters yet: nothing talks to
> PNCP, a database or an LLM, and the three ports the domain declares have no implementations.
> Stage 03 is next. See [docs/architecture.md](docs/architecture.md) for what is built and what is
> planned, and [docs/adr](docs/adr) for why.

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Maven multi-module · Docker Compose ·
JUnit 5, AssertJ, ArchUnit, Testcontainers

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
