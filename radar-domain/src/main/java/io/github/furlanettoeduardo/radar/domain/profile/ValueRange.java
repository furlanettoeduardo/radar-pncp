package io.github.furlanettoeduardo.radar.domain.profile;

import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import java.util.Objects;

/** The contract size a company is willing to bid for. Inclusive at both ends. */
public record ValueRange(MonetaryValue minimum, MonetaryValue maximum) {

  public ValueRange {
    Objects.requireNonNull(minimum, "a value range must have a minimum");
    Objects.requireNonNull(maximum, "a value range must have a maximum");
    if (minimum.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(
          "a value range minimum must not exceed its maximum: %s > %s"
              .formatted(minimum.amount(), maximum.amount()));
    }
  }

  public static ValueRange of(String minimum, String maximum) {
    return new ValueRange(MonetaryValue.of(minimum), MonetaryValue.of(maximum));
  }

  public boolean contains(MonetaryValue value) {
    return minimum.compareTo(value) <= 0 && maximum.compareTo(value) >= 0;
  }
}
