package io.github.furlanettoeduardo.radar.domain.matching;

import java.util.Objects;

/**
 * What a single scoring rule has to say about one procurement and one profile.
 *
 * <p>Rules never see weights or points. A rule reports whether it fired, how strongly, and why; the
 * engine turns that into a score. This is what keeps weights data rather than constants scattered
 * through rule logic.
 */
public sealed interface RuleOutcome {

  /** A human readable justification, carried into the match breakdown. */
  String reason();

  /** The rule fired. {@code strength} in [0,1] scales this rule's weight. */
  record Contributed(double strength, String reason) implements RuleOutcome {

    public Contributed {
      if (strength < 0.0 || strength > 1.0) {
        throw new IllegalArgumentException("strength must be within [0,1] but was " + strength);
      }
      requireReason(reason);
    }
  }

  /** The rule ran and did not fire. Explains why the score is not higher. */
  record Silent(String reason) implements RuleOutcome {

    public Silent {
      requireReason(reason);
    }
  }

  /** The rule could not run because an input it needs is absent. Contributes nothing. */
  record NotApplicable(String reason) implements RuleOutcome {

    public NotApplicable {
      requireReason(reason);
    }
  }

  /**
   * A hard stop. No match is produced at all, whatever every other rule said. Unreachable by any
   * combination of weights, which is the point.
   */
  record Disqualified(String reason) implements RuleOutcome {

    public Disqualified {
      requireReason(reason);
    }
  }

  private static void requireReason(String reason) {
    Objects.requireNonNull(reason, "a rule outcome must carry a reason");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("a rule outcome reason must not be blank");
    }
  }
}
