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

## Modules
- radar-domain: pure domain model and business rules, zero framework dependencies
- radar-ingestion: PNCP client, scheduler, SQS producer and consumer, LLM enrichment
- radar-api: REST controllers, GraphQL, matching queries, auth
- radar-shared: SQS message contracts, common config

## Commands
- Build: mvn -q clean verify
- Run locally: docker compose up
- Integration tests: mvn -q verify -Pintegration (Testcontainers + LocalStack)
- Lint and format: mvn spotless:apply

## Conventions
- Commits: Conventional Commits, imperative mood, in English
- One branch and one PR per roadmap stage, named stage-NN-short-slug
- Every architectural decision gets an ADR in docs/adr using the MADR format
- Tests: JUnit 5, AssertJ, Mockito. Integration tests end with *IT, unit tests with *Test
- No Lombok. Records and explicit code instead.

## What to ask me about instead of guessing
- Anything that changes the AWS footprint or cost
- Anything that changes the PNCP contract assumptions (check docs/samples first)
- Adding a new runtime dependency