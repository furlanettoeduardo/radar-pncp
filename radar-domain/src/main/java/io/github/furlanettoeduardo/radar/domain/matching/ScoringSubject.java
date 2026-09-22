package io.github.furlanettoeduardo.radar.domain.matching;

import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Objects;

/**
 * What the scoring engine evaluates. Procurement and enrichment live in separate aggregates because
 * different processes write them at different times; this pairs them for the read that scoring
 * needs, without putting them back together.
 */
public record ScoringSubject(Procurement procurement) {

  public ScoringSubject {
    Objects.requireNonNull(procurement, "a scoring subject must have a procurement");
  }
}
