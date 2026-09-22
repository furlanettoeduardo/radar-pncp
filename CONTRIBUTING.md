# Contributing

Everything in this repository — code, comments, commit messages, documentation — is written in
English.

## Before you start

Requirements: JDK 21, Maven 3.9+, Docker.

```bash
mvn -q clean verify            # compile, unit tests, format check, architecture rules
mvn -q verify -Pintegration    # the above plus *IT tests on Testcontainers
mvn spotless:apply             # format; the build fails on unformatted code
```

## Branches and pull requests

- One branch and one pull request per roadmap stage, named `stage-NN-short-slug`.
- Pull requests target `main`. `main` stays green.

## Commits

[Conventional Commits](https://www.conventionalcommits.org), imperative mood, English:

```
feat(ingestion): poll PNCP publication endpoint hourly
fix(api): reject matching queries with an empty profile id
docs(adr): record the SQS dead letter queue decision
```

Scopes are module names without the `radar-` prefix: `domain`, `shared`, `ingestion`, `api`, or
`build` and `docs` for cross-cutting changes.

No AI attribution anywhere: no `Co-Authored-By` trailers for assistants, no generated-with
footers, no tool badges.

## Tests come first

A failing test is written before the implementation that makes it pass. Not a test written
afterwards that happens to pass.

- Unit tests end in `*Test` and run under `mvn verify`.
- Integration tests end in `*IT` and run under `mvn verify -Pintegration`.
- JUnit 5, AssertJ, Mockito. ArchUnit for architecture rules.
- Constructor injection in tests too: take collaborators as `@Autowired` constructor parameters,
  never as `@Autowired` fields.

## Code style

- Java 21. Records, sealed interfaces, pattern matching and virtual threads where they fit.
- No Lombok. Records and explicit code instead.
- No field injection. Constructor injection only, and the field is `final`.
- google-java-format, applied by Spotless. `mvn spotless:apply` before committing.

## Architecture rules the build enforces

`radar-domain` and `radar-shared` must not depend on Spring, JPA, Hibernate or Jackson. Two
checks enforce this and both must stay:

- `maven-enforcer-plugin` fails at `validate` when a banned artifact enters the dependency tree.
- `DomainPurityTest` and `SharedContractsPurityTest` fail when a banned package is imported.

If you need a framework annotation on a domain type, the answer is a DTO in the adapter, not a
relaxed rule. The reasoning is in
[ADR 0002](docs/adr/0002-bom-import-and-enforced-domain-purity.md).

## Decisions that need an ADR

Write an ADR in `docs/adr` using the MADR format, in the same pull request as the change, for
anything that:

- changes module boundaries or the direction of a dependency;
- changes the AWS footprint or the cost profile;
- changes the persistence model or introduces a data store;
- adds a runtime dependency;
- changes an assumption about the PNCP contract (check `docs/samples` first).

## Constraints you may not quietly break

- The system runs on a t3.micro with 1 GB of RAM: two JVMs, `-Xmx256m` each. A dependency that
  does not fit is rejected and the rejection is stated, not worked around.
- Costs stay inside the AWS free tier. No EKS, no ALB, no NAT Gateway, no Fargate.

## Definition of done for a stage

- [ ] Tests written first, and they fail before the implementation exists.
- [ ] `mvn -q clean verify` green.
- [ ] `mvn -q verify -Pintegration` green.
- [ ] `mvn spotless:apply` produces no changes.
- [ ] `docs/architecture.md` updated: boxes moved from planned to built, table refreshed.
- [ ] An ADR added for every decision in the list above.
- [ ] README updated if a command, port or module responsibility changed.
