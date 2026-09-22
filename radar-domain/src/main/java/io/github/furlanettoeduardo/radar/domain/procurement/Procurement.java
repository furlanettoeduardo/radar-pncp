package io.github.furlanettoeduardo.radar.domain.procurement;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import java.util.Objects;
import java.util.Optional;

/** A procurement notice published on PNCP. */
public record Procurement(
    PncpControlNumber controlNumber,
    String objectDescription,
    BrazilianState state,
    Optional<MonetaryValue> estimatedValue) {

  public Procurement {
    Objects.requireNonNull(controlNumber, "a procurement must have a control number");
    Objects.requireNonNull(objectDescription, "a procurement must have an object description");
    Objects.requireNonNull(state, "a procurement must have a state");
    Objects.requireNonNull(
        estimatedValue, "estimated value must not be null, use Optional.empty() when secret");
    if (objectDescription.isBlank()) {
      throw new IllegalArgumentException("a procurement object description must not be blank");
    }
  }
}
