# radar-pncp

## What this is
System that ingests Brazilian public procurement notices (PNCP), enriches them
with an LLM, and matches them against company profiles.

## Non-negotiables
- Java 21. Use records, sealed interfaces, pattern matching, virtual threads where they fit.
- Spring Boot 3.x. No field injection, constructor injection only.
- Hexagonal: domain has no Spring or JPA imports. Adapters depend on domain, never the reverse.
- TDD: failing test first, then implementation. No test written after the fact.
- All code, comments, commits, docs in English.
- Must run on a t3.micro (1 GB RAM). Two JVMs, -Xmx256m each. Reject any dependency
  that needs more, and say so instead of adding it.
- Costs must stay inside the AWS free tier. Never suggest EKS, ALB, NAT Gateway, Fargate.
- AWS region is us-east-1, for every resource including stage 8 Terraform. It is the region
  our free tier covers, and keeping everything in one region avoids cross-region transfer
  charges. See docs/adr/0011-aws-region-us-east-1.md.
- Every calendar date is America/Sao_Paulo, computed from an injected Clock, never from
  Instant.now() or the JVM default zone. PNCP's dataInicial and dataFinal are Brazilian
  calendar dates and the host runs in UTC, so for three hours every evening the two disagree.
  DiscoveryCycle is the one place that converts. Tests exist at 01:00 UTC and 23:30 Brasilia.
- radar-api owns the schema. All Flyway migrations live in radar-api/src/main/resources/db/migration
  and only radar-api applies them at startup; radar-ingestion runs with flyway disabled. Other
  modules' integration tests apply those same files from disk (SchemaFixture), never a copy, so
  the build fails when an adapter and the schema drift apart. Operational SQL lives in
  db/operations and is executed by a test for the same reason.
- Measure memory before merging anything into radar-ingestion. Two JVMs share a 400 MiB ceiling
  on a t3.micro and were last measured at 350.5 MiB, so there is about 12% left. The figures and
  the method are in docs/configuration.md.

## Modules
- radar-domain: pure domain model and business rules, zero framework dependencies
- radar-ingestion: PNCP client, scheduler, SQS producer and consumer, LLM enrichment
- radar-api: REST controllers, GraphQL, matching queries, auth
- radar-shared: message contracts only, no framework. Records, nothing else.
  If Spring configuration ever needs to be shared, create a radar-spring-support
  module instead of putting it here.

## Commands
- Build: mvn -q clean verify
- Run locally: docker compose up
- Integration tests: mvn -q verify -Pintegration (Testcontainers; LocalStack once SQS lands)
- Lint and format: mvn spotless:apply

## Conventions
- Commits: Conventional Commits, imperative mood, in English
- One branch and one PR per roadmap stage, named stage-NN-short-slug
- Every architectural decision gets an ADR in docs/adr using the MADR format
- Tests: JUnit 5, AssertJ, Mockito. Integration tests end with *IT, unit tests with *Test
- No Lombok. Records and explicit code instead.
- Queue consumers classify failures before reacting. Poison (bad payload, contract violation,
  schema version too new, constraint violation) fails fast and is left to the redrive policy.
  Transient (the database is unreachable) extends the message's visibility so the three receives
  span an outage instead of ninety seconds. Do NOT classify on Spring's TransientDataAccessException:
  a lost connection is CannotGetJdbcConnectionException, which is a NonTransient one. See
  ConsumerFailures.
- No AI attribution anywhere in the repository. Never add Co-Authored-By trailers,
  "Generated with Claude Code" lines, tool badges, or any other mention of AI
  assistance to commits, pull requests, code comments, or docs.

## What to ask me about instead of guessing
- Anything that changes the AWS footprint or cost
- Anything that changes the PNCP contract assumptions (check docs/samples first)
- Adding a new runtime dependency
