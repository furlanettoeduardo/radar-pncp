package io.github.furlanettoeduardo.radar.domain.enrichment;

/** How sure the enrichment is of its own classification, from 0 to 1 inclusive. */
public record Confidence(double value) {

  public Confidence {
    if (value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException("confidence must be within [0,1] but was " + value);
    }
  }

  public static Confidence of(double value) {
    return new Confidence(value);
  }
}
