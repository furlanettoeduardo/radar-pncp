package io.github.furlanettoeduardo.radar.domain.matching;

import io.github.furlanettoeduardo.radar.domain.enrichment.Enrichment;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Objects;
import java.util.Optional;

/**
 * What the scoring engine evaluates. Procurement and enrichment live in separate aggregates
 * because different processes write them at different times; this pairs them for the read that
 * scoring needs, without putting them back together.
 *
 * <p>An absent enrichment is a normal state, not an error: a procurement is scoreable the moment
 * it is ingested, and the rules that need enrichment report themselves not applicable until it
 * arrives.
 */
public record ScoringSubject(Procurement procurement, Optional<Enrichment> enrichment) {

  public ScoringSubject {
    Objects.requireNonNull(procurement, "a scoring subject must have a procurement");
    Objects.requireNonNull(enrichment, "enrichment must not be null, use Optional.empty()");
  }

  public static ScoringSubject unenriched(Procurement procurement) {
    return new ScoringSubject(procurement, Optional.empty());
  }

  public static ScoringSubject enriched(Procurement procurement, Enrichment enrichment) {
    return new ScoringSubject(procurement, Optional.of(enrichment));
  }
}
