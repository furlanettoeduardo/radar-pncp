package io.github.furlanettoeduardo.radar.domain.matching;

import java.util.Objects;

/**
 * One line of a match breakdown: which rule spoke, what it said, and what that was worth.
 *
 * <p>The outcome is carried whole rather than flattened to a string, so a reader can tell a rule
 * that found nothing apart from one that could not run. Both score zero; only one of them is the
 * procurement failing a criterion.
 */
public record MatchReason(RuleId rule, RuleOutcome outcome, int points) {

  public MatchReason {
    Objects.requireNonNull(rule, "a match reason must name a rule");
    Objects.requireNonNull(outcome, "a match reason must carry the rule outcome");
    if (points < 0) {
      throw new IllegalArgumentException("a match reason cannot contribute negative points");
    }
  }

  public String reason() {
    return outcome.reason();
  }
}
