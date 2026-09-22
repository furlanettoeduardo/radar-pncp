package io.github.furlanettoeduardo.radar.domain.matching.rule;

import java.time.Duration;
import java.util.Objects;

/**
 * How much time a supplier needs, expressed as data so it can be tuned without touching rule
 * logic.
 *
 * <p>Beyond {@code comfortable} there is enough runway to assemble documents without hurrying, and
 * more time buys nothing further. At or inside {@code viable} the notice is barely actionable and
 * scores the floor. Between the two, strength falls linearly.
 *
 * <p>The floor sits above zero on purpose. A notice closing tomorrow is still winnable by a
 * supplier whose paperwork is ready; scoring it zero would be a soft disqualification, and the
 * rule already has a hard one for deadlines that have actually passed.
 */
public record DeadlineHorizon(Duration comfortable, Duration viable, double floorStrength) {

  public DeadlineHorizon {
    Objects.requireNonNull(comfortable, "a deadline horizon must have a comfortable duration");
    Objects.requireNonNull(viable, "a deadline horizon must have a viable duration");
    if (comfortable.compareTo(viable) <= 0) {
      throw new IllegalArgumentException(
          "the comfortable horizon must be longer than the viable one: %s <= %s"
              .formatted(comfortable, viable));
    }
    if (viable.isNegative() || viable.isZero()) {
      throw new IllegalArgumentException("the viable horizon must be positive but was " + viable);
    }
    if (floorStrength < 0.0 || floorStrength >= 1.0) {
      throw new IllegalArgumentException(
          "the floor strength must be within [0,1) but was " + floorStrength);
    }
  }

  /** Fourteen days of runway, two days as the practical floor, one tenth of the weight there. */
  public static DeadlineHorizon standard() {
    return new DeadlineHorizon(Duration.ofDays(14), Duration.ofDays(2), 0.10);
  }
}
