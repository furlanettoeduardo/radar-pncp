package io.github.furlanettoeduardo.radar.domain.matching;

import static io.github.furlanettoeduardo.radar.domain.EnrichmentBuilder.anEnrichment;
import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
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
import java.util.Random;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Score level invariants, over inputs no example test would think to write.
 *
 * <p>Seeded explicitly, and {@link Random} has a specified algorithm, so a failure reproduces
 * exactly. Every assertion carries the seed and the generating case in its description.
 */
class ScoringEngineInvariantsTest {

  private static final long SEED = 20260922L;
  private static final int TRIALS = 500;
  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

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
  @DisplayName("a score is never below 0 and never above 100")
  void scoreStaysWithinBounds() {
    forEachCase(
        generated -> {
          ScoringResult result = evaluate(generated);
          if (result instanceof ScoringResult.Matched matched) {
            assertThat(matched.match().score().value()).as(generated.describe()).isBetween(0, 100);
            assertThat(matched.match().evidenceCoverage().percent())
                .as(generated.describe())
                .isBetween(0, 100);
          } else if (result instanceof ScoringResult.BelowThreshold below) {
            assertThat(below.score().value()).as(generated.describe()).isBetween(0, 100);
          }
        });
  }

  @Test
  @DisplayName("a disqualified procurement never carries a score at all")
  void disqualificationCarriesNoScore() {
    forEachCase(
        generated ->
            assertThat(evaluate(generated))
                .as(generated.describe())
                .satisfiesAnyOf(
                    result -> assertThat(result).isInstanceOf(ScoringResult.Matched.class),
                    result -> assertThat(result).isInstanceOf(ScoringResult.BelowThreshold.class),
                    result ->
                        assertThat(result)
                            .isInstanceOfSatisfying(
                                ScoringResult.Disqualified.class,
                                disqualified -> assertThat(disqualified.reason()).isNotBlank())));
  }

  @Test
  @DisplayName("evaluating the same inputs twice gives the same result, identity included")
  void evaluationIsDeterministic() {
    forEachCase(
        generated -> {
          // Built once: two builder calls would mint two profile ids, which is not the same input.
          ScoringSubject subject = generated.subject();
          SearchProfile profile = generated.profile();

          assertThat(engine.evaluate(subject, profile, NOW))
              .as(generated.describe())
              .isEqualTo(engine.evaluate(subject, profile, NOW));
        });
  }

  private ScoringResult evaluate(GeneratedCase generated) {
    return engine.evaluate(generated.subject(), generated.profile(), NOW);
  }

  private void forEachCase(Consumer<GeneratedCase> assertion) {
    RandomGenerator random = new Random(SEED);
    IntStream.range(0, TRIALS).forEach(trial -> assertion.accept(generate(random, trial)));
  }

  private static GeneratedCase generate(RandomGenerator random, int trial) {
    return new GeneratedCase(
        trial,
        words(random, 1 + random.nextInt(4)),
        String.join(" ", words(random, 1 + random.nextInt(20))),
        BrazilianState.values()[random.nextInt(BrazilianState.values().length)],
        BrazilianState.values()[random.nextInt(BrazilianState.values().length)],
        random.nextBoolean(),
        random.nextInt(60) - 10,
        random.nextBoolean(),
        ProcurementSegment.values()[random.nextInt(ProcurementSegment.values().length)],
        random.nextDouble(),
        random.nextInt(101));
  }

  private static List<String> words(RandomGenerator random, int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                IntStream.rangeClosed(0, random.nextInt(8))
                    .mapToObj(c -> String.valueOf((char) ('a' + random.nextInt(26))))
                    .collect(Collectors.joining()))
        .toList();
  }

  private record GeneratedCase(
      int trial,
      List<String> keywords,
      String objectDescription,
      BrazilianState procurementState,
      BrazilianState wantedState,
      boolean budgetVisible,
      int daysUntilClosing,
      boolean enriched,
      ProcurementSegment segment,
      double confidence,
      int threshold) {

    ScoringSubject subject() {
      var procurement =
          aProcurement()
              .describing(objectDescription)
              .in(procurementState)
              .closingAt(NOW.plus(Duration.ofDays(daysUntilClosing)));
      var built =
          budgetVisible
              ? procurement.worth("50000").build()
              : procurement.withSecretBudget().build();
      return enriched
          ? ScoringSubject.enriched(
              built, anEnrichment().forSegment(segment).confident(confidence).build())
          : ScoringSubject.unenriched(built);
    }

    SearchProfile profile() {
      return aProfile()
          .withKeywords(keywords.toArray(String[]::new))
          .withCnaes("6201-5/01")
          .in(wantedState)
          .worthBetween("10000", "100000")
          .scoringAtLeast(threshold)
          .build();
    }

    String describe() {
      return "seed=%d trial=%d keywords=%s object=%s state=%s wanted=%s budget=%s days=%d enriched=%s segment=%s confidence=%s threshold=%d"
          .formatted(
              SEED,
              trial,
              keywords,
              objectDescription,
              procurementState,
              wantedState,
              budgetVisible,
              daysUntilClosing,
              enriched,
              segment,
              confidence,
              threshold);
    }
  }
}
