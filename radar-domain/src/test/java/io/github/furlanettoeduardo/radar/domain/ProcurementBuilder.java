package io.github.furlanettoeduardo.radar.domain;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.procurement.Modality;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.time.Instant;
import java.util.Optional;

/** Test data builder, so growing the Procurement record does not ripple through every test. */
public final class ProcurementBuilder {

  private PncpControlNumber controlNumber = new PncpControlNumber("44935278000126-1-000343/2025");
  private String objectDescription = "Aquisicao de brinquedos pedagogicos educativos";
  private BrazilianState state = BrazilianState.SP;
  private Optional<MonetaryValue> estimatedValue = Optional.of(MonetaryValue.of("50000.00"));
  private Instant proposalClosesAt = Instant.parse("2026-10-20T12:00:00Z");
  private Modality modality = Modality.of(6, "Pregao - Eletronico");
  private Instant publishedAt = Instant.parse("2026-09-01T14:20:41Z");
  private Instant proposalOpensAt = Instant.parse("2026-09-02T08:00:00Z");
  private String sourcePayloadHash = "0f5d1a5b1c2f4e6a8b9c0d1e2f3a4b5c";
  private Optional<Instant> sourceUpdatedAt = Optional.of(Instant.parse("2026-09-01T17:22:01Z"));

  private ProcurementBuilder() {}

  public static ProcurementBuilder aProcurement() {
    return new ProcurementBuilder();
  }

  public ProcurementBuilder identifiedBy(String controlNumber) {
    this.controlNumber = new PncpControlNumber(controlNumber);
    return this;
  }

  public ProcurementBuilder describing(String objectDescription) {
    this.objectDescription = objectDescription;
    return this;
  }

  public ProcurementBuilder in(BrazilianState state) {
    this.state = state;
    return this;
  }

  public ProcurementBuilder worth(String amount) {
    this.estimatedValue = Optional.of(MonetaryValue.of(amount));
    return this;
  }

  public ProcurementBuilder withSecretBudget() {
    this.estimatedValue = Optional.empty();
    return this;
  }

  public ProcurementBuilder closingAt(Instant proposalClosesAt) {
    this.proposalClosesAt = proposalClosesAt;
    return this;
  }

  public ProcurementBuilder openingAt(Instant proposalOpensAt) {
    this.proposalOpensAt = proposalOpensAt;
    return this;
  }

  public ProcurementBuilder hashed(String sourcePayloadHash) {
    this.sourcePayloadHash = sourcePayloadHash;
    return this;
  }

  public ProcurementBuilder updatedAt(Instant sourceUpdatedAt) {
    this.sourceUpdatedAt = Optional.of(sourceUpdatedAt);
    return this;
  }

  public ProcurementBuilder withoutSourceTimestamp() {
    this.sourceUpdatedAt = Optional.empty();
    return this;
  }

  public Procurement build() {
    return new Procurement(
        controlNumber,
        objectDescription,
        state,
        estimatedValue,
        modality,
        publishedAt,
        proposalOpensAt,
        proposalClosesAt,
        sourcePayloadHash,
        sourceUpdatedAt);
  }
}
