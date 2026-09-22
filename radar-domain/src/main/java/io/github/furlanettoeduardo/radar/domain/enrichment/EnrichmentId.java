package io.github.furlanettoeduardo.radar.domain.enrichment;

import java.util.Objects;
import java.util.UUID;

/** Identity of an {@code Enrichment} aggregate. */
public record EnrichmentId(UUID value) {

  public EnrichmentId {
    Objects.requireNonNull(value, "an enrichment id must have a value");
  }
}
