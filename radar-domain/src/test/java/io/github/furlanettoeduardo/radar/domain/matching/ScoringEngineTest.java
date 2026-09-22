package io.github.furlanettoeduardo.radar.domain.matching;

import static io.github.furlanettoeduardo.radar.domain.EnrichmentBuilder.anEnrichment;
import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.Score;
import io.github.furlanettoeduardo.radar.domain.enrichment.ProcurementSegment;
import io.github.furlanettoeduardo.radar.domain.matching.rule.DeadlineHorizon;
import io.github.furlanettoeduardo.radar.domain.matching.rule.EstimatedValueRule;
import io.github.furlanettoeduardo.radar.domain.matching.rule.KeywordMatchRule;
import io.github.furlanettoeduardo.radar.domain.matching.rule.ProposalDeadlineRule;
import io.github.furlanettoeduardo.radar.domain.matching.rule.SegmentMatchRule;
import io.github.furlanettoeduardo.radar.domain.matching.rule.StateMatchRule;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoringEngineTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
  private static final Instant COMFORTABLY_OPEN = NOW.plus(Duration.ofDays(21));

  private final ScoringEngine engine =
      new ScoringEngine(
          List.of(
              new KeywordMatchRule(),
              new SegmentMatchRule(),
              new EstimatedValueRule(),
              new StateMatchRule(),
              new ProposalDeadlineRule(DeadlineHorizon.standard())),
          WeightingScheme.standard());

  @Test
  @DisplayName("a closed deadline produces no match and no score at all")
  void aClosedDeadlineProducesNoScore() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement().closingAt(NOW.minus(Duration.ofDays(1))).build(),
            anEnrichment().forSegment(ProcurementSegment.IT_SERVICES).confident(1.0).build());

    ScoringResult result = engine.evaluate(subject, perfectProfile(), NOW);

    assertThat(result)
        .isInstanceOfSatisfying(
            ScoringResult.Disqualified.class,
            disqualified -> assertThat(disqualified.reason()).contains("closed"));
  }

  @Test
  @DisplayName("every rule at full strength scores one hundred on full evidence")
  void everyRuleAtFullStrengthScoresOneHundred() {
    ScoringResult result = engine.evaluate(perfectSubject(), perfectProfile(), NOW);

    assertThat(result)
        .isInstanceOfSatisfying(
            ScoringResult.Matched.class,
            matched -> {
              assertThat(matched.match().score()).isEqualTo(Score.of(100));
              assertThat(matched.match().evidenceCoverage()).isEqualTo(EvidenceCoverage.of(100));
            });
  }

  @Test
  @DisplayName("a hidden budget lowers the evidence coverage rather than acting as a penalty")
  void aHiddenBudgetLowersCoverage() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement()
                .describing("Aquisicao de computadores e servicos de informatica")
                .in(BrazilianState.SP)
                .withSecretBudget()
                .closingAt(COMFORTABLY_OPEN)
                .build(),
            anEnrichment().forSegment(ProcurementSegment.IT_SERVICES).confident(1.0).build());

    ScoringResult result = engine.evaluate(subject, perfectProfile(), NOW);

    assertThat(result)
        .isInstanceOfSatisfying(
            ScoringResult.Matched.class,
            matched -> {
              assertThat(matched.match().score()).isEqualTo(Score.of(85));
              assertThat(matched.match().evidenceCoverage()).isEqualTo(EvidenceCoverage.of(85));
            });
  }

  @Test
  @DisplayName("a score under the profile threshold is reported, not silently dropped")
  void belowTheThresholdIsNotAMatch() {
    ScoringResult result = engine.evaluate(wrongTradeSubject(), perfectProfile(), NOW);

    assertThat(result)
        .isInstanceOfSatisfying(
            ScoringResult.BelowThreshold.class,
            below -> {
              assertThat(below.score()).isEqualTo(Score.of(40));
              assertThat(below.threshold()).isEqualTo(Score.of(50));
            });
  }

  @Test
  @DisplayName("geography, budget and timing together cannot reach the threshold on their own")
  void capabilityIsRequiredToMatch() {
    ScoringResult result = engine.evaluate(wrongTradeSubject(), perfectProfile(), NOW);

    assertThat(result).isInstanceOf(ScoringResult.BelowThreshold.class);
    assertThat(((ScoringResult.BelowThreshold) result).score().value()).isLessThan(50);
  }

  @Test
  @DisplayName("the breakdown is ordered by contribution and keeps what could not be evaluated")
  void theBreakdownIsOrderedAndComplete() {
    ScoringSubject subject =
        ScoringSubject.unenriched(
            aProcurement()
                .describing("Aquisicao de computadores e servicos de informatica")
                .in(BrazilianState.SP)
                .worth("50000")
                .closingAt(COMFORTABLY_OPEN)
                .build());

    ScoringResult result = engine.evaluate(subject, perfectProfile(), NOW);

    assertThat(result)
        .isInstanceOfSatisfying(
            ScoringResult.Matched.class,
            matched -> {
              List<MatchReason> reasons = matched.match().reasons();
              assertThat(reasons).hasSize(5);
              assertThat(reasons.get(0).points()).isEqualTo(30);
              assertThat(reasons)
                  .anySatisfy(
                      reason -> {
                        assertThat(reason.rule()).isEqualTo(RuleId.SEGMENT);
                        assertThat(reason.outcome()).isInstanceOf(RuleOutcome.NotApplicable.class);
                        assertThat(reason.points()).isZero();
                      });
              assertThat(matched.match().evidenceCoverage()).isEqualTo(EvidenceCoverage.of(70));
            });
  }

  private static ScoringSubject perfectSubject() {
    return ScoringSubject.enriched(
        aProcurement()
            .describing("Aquisicao de computadores e servicos de informatica")
            .in(BrazilianState.SP)
            .worth("50000")
            .closingAt(COMFORTABLY_OPEN)
            .build(),
        anEnrichment().forSegment(ProcurementSegment.IT_SERVICES).confident(1.0).build());
  }

  private static ScoringSubject wrongTradeSubject() {
    return ScoringSubject.enriched(
        aProcurement()
            .describing("Pavimentacao asfaltica de vias urbanas")
            .in(BrazilianState.SP)
            .worth("50000")
            .closingAt(COMFORTABLY_OPEN)
            .build(),
        anEnrichment().forSegment(ProcurementSegment.CIVIL_WORKS).confident(1.0).build());
  }

  private static SearchProfile perfectProfile() {
    return aProfile()
        .withKeywords("computador", "informatica")
        .withCnaes("6201-5/01")
        .in(BrazilianState.SP)
        .worthBetween("10000", "100000")
        .scoringAtLeast(50)
        .build();
  }
}
