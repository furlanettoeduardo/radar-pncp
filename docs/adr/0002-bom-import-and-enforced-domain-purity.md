---
status: accepted
date: 2026-09-21
decision-makers: Eduardo Furlanetto
---

# Import the Spring Boot BOM and enforce domain purity at build time

## Context and Problem Statement

The central rule of this codebase is that `radar-domain` holds business rules and nothing else:
no Spring, no JPA, no Hibernate, no Jackson. A rule that lives only in a README is a rule that
survives until the first deadline. Two structural decisions in the foundation stage exist to
make that rule mechanical, and they are recorded together because the second depends on the
first.

## Decision 1: import `spring-boot-dependencies` instead of inheriting `spring-boot-starter-parent`

### Considered Options

- **Parent POM inherits `spring-boot-starter-parent`** — the default in every Spring Boot guide.
- **Plain parent POM that imports `spring-boot-dependencies` as a BOM** — chosen.

### Decision Outcome

Chosen option: **import the BOM**.

Inheriting `spring-boot-starter-parent` is inheritance, not just version management. It brings
resource filtering, a `-parameters` compiler default, plugin configuration and a `start-class`
property into *every* module, including `radar-domain` and `radar-shared`, which must carry no
Spring footprint at all. A module whose build is configured by Spring is not a module free of
Spring, even if its dependency list is empty.

Importing `spring-boot-dependencies` in `dependencyManagement` gives the version alignment,
which is the part we actually want, and leaves the pure modules with a build that mentions
Spring nowhere.

### Consequences

- Good, because `radar-domain` and `radar-shared` inherit no framework configuration whatsoever.
- Good, because plugin versions are pinned explicitly in one place, visible rather than inherited.
- Bad, because the conveniences of `spring-boot-starter-parent` have to be reproduced by hand.
  Two of them bit during this stage and are now configured in the parent POM:
  - `maven-compiler-plugin` needs `<parameters>true</parameters>`, or Jackson and Spring cannot
    read record component names.
  - `maven-failsafe-plugin` needs `<classesDirectory>${project.build.outputDirectory}</classesDirectory>`,
    or it runs integration tests against the repackaged fat jar, where the classes sit under
    `BOOT-INF/classes` and `@SpringBootConfiguration` becomes invisible to Spring Test.
- Bad, because a Spring Boot upgrade may introduce new parent defaults we will not get for free.
  Accepted: the upgrade is a deliberate, reviewed act in this project either way.

## Decision 2: ban framework artifacts from `radar-domain` with `maven-enforcer-plugin`

### Considered Options

- **Convention and code review** — document the rule, trust the reviewer.
- **`maven-enforcer-plugin` `bannedDependencies` on `radar-domain`** — chosen, with ArchUnit.
- **A separate repository for the domain** — physical separation, maximum ceremony.

### Decision Outcome

Chosen option: **`bannedDependencies` in the build, plus an ArchUnit test**. The banned list is
`org.springframework*`, `jakarta.persistence`, `javax.persistence`, `org.hibernate*` and
`com.fasterxml.jackson*`, searched transitively. `radar-shared` carries the same rule, because
it holds message contracts only.

The two checks cover different failures and both are kept:

- The enforcer rule catches a **declared or transitive dependency**. It fails at `validate`,
  before anything compiles, with a message naming the offending artifact.
- The ArchUnit test (`DomainPurityTest`, `SharedContractsPurityTest`) catches an **import** that
  arrives through a dependency that is present for a legitimate reason. The enforcer cannot see
  that; ArchUnit can.

Both were verified against a deliberate violation before this stage was merged: adding
`spring-core` to `radar-domain` fails the enforcer at `validate`, and a class importing
`org.springframework.util.StringUtils` fails `DomainPurityTest`.

### The Jackson ban is the point, not an oversight

Banning Spring surprises nobody. Banning Jackson does, and it is the rule that will be
questioned first, the day someone wants `@JsonProperty` on a domain record.

The correct answer that day is a DTO in the adapter that owns the serialization, mapped to and
from the domain type. A domain record annotated for one wire format has quietly acquired a
second responsibility and a second reason to change: rename a field for the JSON contract and
the business model moves with it. The ban exists so that this trade-off is made explicitly, in a
pull request that has to justify relaxing it, rather than silently by whoever is closest to a
deadline.

### Consequences

- Good, because the architecture rule fails the build instead of depending on a reviewer's mood.
- Good, because the failure message explains the intent, not just the violation.
- Bad, because adapters need explicit mapping code between DTOs and domain types. That is the
  price of the boundary, and it is being paid on purpose.
