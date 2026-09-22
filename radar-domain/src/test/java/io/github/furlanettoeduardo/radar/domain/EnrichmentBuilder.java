package io.github.furlanettoeduardo.radar.domain;

import io.github.furlanettoeduardo.radar.domain.enrichment.Confidence;
import io.github.furlanettoeduardo.radar.domain.enrichment.Enrichment;
import io.github.furlanettoeduardo.radar.domain.enrichment.EnrichmentId;
import io.github.furlanettoeduardo.radar.domain.enrichment.ProcurementSegment;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import java.time.Instant;
import java.util.UUID;

/** Test data builder for the Enrichment aggregate. */
public final class EnrichmentBuilder {

  private ProcurementSegment segment = ProcurementSegment.OTHER;
  private Confidence confidence = Confidence.of(1.0);

  private EnrichmentBuilder() {}

  public static EnrichmentBuilder anEnrichment() {
    return new EnrichmentBuilder();
  }

  public EnrichmentBuilder forSegment(ProcurementSegment segment) {
    this.segment = segment;
    return this;
  }

  public EnrichmentBuilder confident(double confidence) {
    this.confidence = Confidence.of(confidence);
    return this;
  }

  public Enrichment build() {
    return new Enrichment(
        new EnrichmentId(UUID.randomUUID()),
        new PncpControlNumber("44935278000126-1-000343/2025"),
        segment,
        confidence,
        Instant.parse("2026-09-22T10:00:00Z"));
  }
}
