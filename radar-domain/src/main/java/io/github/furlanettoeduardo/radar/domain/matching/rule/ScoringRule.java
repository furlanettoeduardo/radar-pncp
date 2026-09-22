package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.matching.RuleId;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Instant;

/**
 * One independent, individually testable scoring criterion.
 *
 * <p>The evaluation instant is a parameter rather than a call to {@code Instant.now()} inside a
 * rule. That is what makes the engine deterministic for the same inputs, and therefore testable.
 */
public interface ScoringRule {

  /** Names this rule, so a weighting scheme can price it without the rule knowing. */
  RuleId id();

  RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt);
}
