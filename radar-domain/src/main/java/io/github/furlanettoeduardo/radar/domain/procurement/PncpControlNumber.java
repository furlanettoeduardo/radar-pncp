package io.github.furlanettoeduardo.radar.domain.procurement;

import java.util.Objects;

/**
 * Natural identity of a procurement, as PNCP publishes it in {@code numeroControlePNCP}, for
 * example {@code 44935278000126-1-000343/2025}.
 *
 * <p>The format is not validated here. The samples show one shape, which is not enough evidence to
 * reject everything else, and a domain that refuses to hold a notice PNCP actually published would
 * be wrong in a way that is expensive to discover.
 */
public record PncpControlNumber(String value) {

  public PncpControlNumber {
    Objects.requireNonNull(value, "a PNCP control number must have a value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("a PNCP control number must not be blank");
    }
  }
}
