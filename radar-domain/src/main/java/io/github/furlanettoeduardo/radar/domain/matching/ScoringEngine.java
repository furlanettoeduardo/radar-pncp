package io.github.furlanettoeduardo.radar.domain.matching;

import io.github.furlanettoeduardo.radar.domain.common.Score;
import io.github.furlanettoeduardo.radar.domain.matching.rule.ScoringRule;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Turns independent rule outcomes into one explainable score.
 *
 * <p>The engine owns the arithmetic and the rules own the judgement. A rule reports whether it
 * fired and how strongly; the engine multiplies that by the rule weight. Nothing here knows what a
 * keyword is, and no rule knows what it is worth.
 *
 * <p>A single disqualifying outcome ends the evaluation. No score is computed, because a score
 * would invite somebody to tune their way past it.
 *
 * <p>The evaluation instant is a parameter. The engine never reads the clock, which is what makes a
 * match reproducible and a test honest.
 */
public final class ScoringEngine {

  private final List<ScoringRule> rules;
  private final WeightingScheme weights;

  public ScoringEngine(List<ScoringRule> rules, WeightingScheme weights) {
    Objects.requireNonNull(rules, "a scoring engine needs rules");
    this.weights = Objects.requireNonNull(weights, "a scoring engine needs a weighting scheme");
    if (rules.isEmpty()) {
      throw new IllegalArgumentException("a scoring engine without rules cannot score anything");
    }
    this.rules = List.copyOf(rules);
  }

  public ScoringResult evaluate(
      ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    List<MatchReason> breakdown = new ArrayList<>();
    double points = 0.0;
    int coverage = 0;

    for (ScoringRule rule : rules) {
      RuleOutcome outcome = rule.evaluate(subject, profile, evaluatedAt);
      int weight = weights.weightOf(rule.id());

      if (outcome instanceof RuleOutcome.Disqualified disqualified) {
        return new ScoringResult.Disqualified(rule.id(), disqualified.reason());
      }

      int contributed = 0;
      if (outcome instanceof RuleOutcome.Contributed fired) {
        double earned = weight * fired.strength();
        points += earned;
        contributed = (int) Math.round(earned);
      }
      if (!(outcome instanceof RuleOutcome.NotApplicable)) {
        coverage += weight;
      }
      breakdown.add(new MatchReason(rule.id(), outcome, contributed));
    }

    Score score = Score.of((int) Math.round(points));
    List<MatchReason> reasons =
        breakdown.stream()
            .sorted(
                Comparator.comparingInt(MatchReason::points)
                    .reversed()
                    .thenComparing(reason -> reason.rule().name()))
            .toList();

    if (!score.isAtLeast(profile.minimumScore())) {
      return new ScoringResult.BelowThreshold(score, profile.minimumScore(), reasons);
    }
    return new ScoringResult.Matched(
        new Match(
            MatchId.of(subject.procurement().controlNumber(), profile.id()),
            subject.procurement().controlNumber(),
            profile.id(),
            score,
            EvidenceCoverage.of(coverage),
            reasons,
            evaluatedAt));
  }
}
