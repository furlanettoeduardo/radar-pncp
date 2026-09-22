package io.github.furlanettoeduardo.radar.domain.common;

/**
 * A score from 0 to 100 inclusive. Lives here rather than in the matching package so that a profile
 * can carry a threshold without the two packages depending on each other.
 */
public record Score(int value) {

  public Score {
    if (value < 0 || value > 100) {
      throw new IllegalArgumentException("a score must be within [0,100] but was " + value);
    }
  }

  public static Score of(int value) {
    return new Score(value);
  }

  public boolean isAtLeast(Score other) {
    return value >= other.value;
  }
}
