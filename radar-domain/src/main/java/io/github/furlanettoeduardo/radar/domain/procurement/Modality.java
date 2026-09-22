package io.github.furlanettoeduardo.radar.domain.procurement;

import java.util.Objects;

/**
 * The procurement modality, as PNCP publishes it in {@code modalidadeId} and {@code
 * modalidadeNome}.
 *
 * <p>A record rather than an enum, deliberately. The recorded samples contain exactly one value,
 * code 6, and writing out an enum of every modality would be inventing a closed set from a single
 * observation. {@code BrazilianState} is an enum because that set is genuinely closed and defined
 * outside this system; this one is not, from where we stand.
 */
public record Modality(int code, String name) {

  public Modality {
    Objects.requireNonNull(name, "a modality must have a name");
    if (code <= 0) {
      throw new IllegalArgumentException("a modality code must be positive but was " + code);
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("a modality name must not be blank");
    }
  }

  public static Modality of(int code, String name) {
    return new Modality(code, name);
  }
}
