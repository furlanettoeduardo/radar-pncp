package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.matching.RuleId;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Instant;
import java.util.Set;

/**
 * Scores whether the procurement was published somewhere the company is willing to serve.
 *
 * <p>All or nothing, deliberately. A neighbouring state is not partially useful: either the company
 * can deliver there or it cannot, and inventing a distance gradient would be modelling geography
 * this system has no data for.
 *
 * <p>A profile that names no state means anywhere, so the rule cannot discriminate and reports
 * itself not applicable rather than silently awarding or withholding its weight.
 */
public final class StateMatchRule implements ScoringRule {

  @Override
  public RuleId id() {
    return RuleId.STATE;
  }

  @Override
  public RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    Set<BrazilianState> wanted = profile.states();
    if (wanted.isEmpty()) {
      return new RuleOutcome.NotApplicable(
          "the profile names no states, so geography cannot tell procurements apart");
    }

    BrazilianState actual = subject.procurement().state();
    if (wanted.contains(actual)) {
      return new RuleOutcome.Contributed(
          1.0, "published in %s, which the profile asks for".formatted(actual));
    }
    return new RuleOutcome.Silent(
        "published in %s, which the profile does not ask for".formatted(actual));
  }
}
