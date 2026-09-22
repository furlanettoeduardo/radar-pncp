package io.github.furlanettoeduardo.radar.domain;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Optional;

/** Test data builder, so growing the Procurement record does not ripple through every test. */
public final class ProcurementBuilder {

  private PncpControlNumber controlNumber = new PncpControlNumber("44935278000126-1-000343/2025");
  private String objectDescription = "Aquisicao de brinquedos pedagogicos educativos";
  private BrazilianState state = BrazilianState.SP;
  private Optional<MonetaryValue> estimatedValue = Optional.of(MonetaryValue.of("50000.00"));

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

  public Procurement build() {
    return new Procurement(controlNumber, objectDescription, state, estimatedValue);
  }
}
