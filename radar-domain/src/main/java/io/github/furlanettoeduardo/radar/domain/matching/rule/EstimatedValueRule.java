package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import io.github.furlanettoeduardo.radar.domain.profile.ValueRange;
import java.time.Instant;
import java.util.Optional;

/**
 * Scores whether the contract is the size this company can take on. A contract an order of
 * magnitude too large is not an opportunity, it is a week of wasted proposal writing.
 *
 * <p>PNCP allows a procurement to hide its budget, {@code orcamentoSigiloso}. That is reported as
 * not applicable rather than as a zero: the supplier did not fail this criterion, the criterion
 * could not be evaluated, and the difference is visible in the match's evidence coverage.
 */
public final class EstimatedValueRule implements ScoringRule {

  @Override
  public RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    Optional<ValueRange> range = profile.valueRange();
    if (range.isEmpty()) {
      return new RuleOutcome.NotApplicable("the profile declares no value range");
    }

    Optional<MonetaryValue> estimated = subject.procurement().estimatedValue();
    if (estimated.isEmpty()) {
      return new RuleOutcome.NotApplicable(
          "the procurement carries no estimated value, the budget is secret");
    }

    ValueRange wanted = range.get();
    MonetaryValue value = estimated.get();
    if (wanted.contains(value)) {
      return new RuleOutcome.Contributed(
          1.0,
          "estimated at %s, inside the %s to %s the profile asks for"
              .formatted(value.amount(), wanted.minimum().amount(), wanted.maximum().amount()));
    }
    return new RuleOutcome.Silent(
        "estimated at %s, outside the %s to %s the profile asks for"
            .formatted(value.amount(), wanted.minimum().amount(), wanted.maximum().amount()));
  }
}
