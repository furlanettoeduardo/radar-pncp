package io.github.furlanettoeduardo.radar.domain.enrichment;

import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import java.time.Instant;
import java.util.Objects;

/**
 * What a language model concluded about one procurement.
 *
 * <p>Its own aggregate, referencing the procurement by identity rather than living inside it: a
 * different process writes it, at a different time, it can fail, and it can be produced again
 * later without the procurement changing at all.
 */
public record Enrichment(
    EnrichmentId id,
    PncpControlNumber procurement,
    ProcurementSegment segment,
    Confidence confidence,
    Instant generatedAt) {

  public Enrichment {
    Objects.requireNonNull(id, "an enrichment must have an id");
    Objects.requireNonNull(procurement, "an enrichment must belong to a procurement");
    Objects.requireNonNull(segment, "an enrichment must carry a segment");
    Objects.requireNonNull(confidence, "an enrichment must carry a confidence");
    Objects.requireNonNull(generatedAt, "an enrichment must record when it was generated");
  }
}
