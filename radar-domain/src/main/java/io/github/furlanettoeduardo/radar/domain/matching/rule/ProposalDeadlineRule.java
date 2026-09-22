package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The only disqualifying rule. A procurement whose proposal window has closed is not a weak match,
 * it is not a match: no score is computed and no amount of weight can bring it back.
 *
 * <p>While the window is open the rule still ranks, because time is a real constraint rather than
 * a yes or no. A notice closing in 36 hours is not actionable for a small supplier assembling
 * certificates, and scoring it identically to one closing in three weeks would rank nothing at
 * all. The curve comes from {@link DeadlineHorizon}.
 *
 * <p>Deadlines are compared as instants. PNCP publishes naive local timestamps and the conversion
 * to an instant happens at the adapter boundary, pinned to America/Sao_Paulo. Because this rule
 * disqualifies, an hour of drift there deletes matches silently, which is why that conversion is
 * fixed and tested rather than inferred.
 */
public final class ProposalDeadlineRule implements ScoringRule {

  private final DeadlineHorizon horizon;

  public ProposalDeadlineRule(DeadlineHorizon horizon) {
    this.horizon = Objects.requireNonNull(horizon, "a deadline rule needs a horizon");
  }

  @Override
  public RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    Instant closesAt = subject.procurement().proposalClosesAt();
    if (!evaluatedAt.isBefore(closesAt)) {
      return new RuleOutcome.Disqualified(
          "the proposal window closed at %s, before the evaluation at %s"
              .formatted(closesAt, evaluatedAt));
    }

    Duration remaining = Duration.between(evaluatedAt, closesAt);
    return new RuleOutcome.Contributed(
        strengthFor(remaining),
        "proposals close at %s, %d days away".formatted(closesAt, remaining.toDays()));
  }

  private double strengthFor(Duration remaining) {
    if (remaining.compareTo(horizon.comfortable()) >= 0) {
      return 1.0;
    }
    if (remaining.compareTo(horizon.viable()) <= 0) {
      return horizon.floorStrength();
    }
    double span = horizon.comfortable().minus(horizon.viable()).toNanos();
    double above = remaining.minus(horizon.viable()).toNanos();
    return horizon.floorStrength() + (1.0 - horizon.floorStrength()) * (above / span);
  }
}
