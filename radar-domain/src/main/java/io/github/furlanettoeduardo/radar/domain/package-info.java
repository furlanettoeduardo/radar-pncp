/**
 * Pure domain model and business rules for radar-pncp.
 *
 * <p>Nothing in this module may depend on Spring, JPA, Hibernate or Jackson. Persistence,
 * serialization and dependency injection live in the adapters; the domain only knows plain Java.
 * The rule is enforced twice: by the maven-enforcer-plugin on the dependency tree and by {@code
 * DomainPurityTest} on the imports.
 */
package io.github.furlanettoeduardo.radar.domain;
