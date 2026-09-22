package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariants that must hold for inputs no example test thought of.
 *
 * <p>The generator is seeded explicitly and {@link Random} has a specified algorithm, so a failure
 * here reproduces exactly. Every assertion carries the seed and the offending case in its
 * description, which is what gets printed when one fails.
 */
class KeywordMatchRuleInvariantsTest {

  private static final long SEED = 20260922L;
  private static final int TRIALS = 500;
  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final KeywordMatchRule rule = new KeywordMatchRule();

  @Test
  @DisplayName("strength never leaves the unit interval, whatever the inputs")
  void strengthStaysWithinTheUnitInterval() {
    forEachGeneratedCase(
        generated -> {
          if (rule.evaluate(generated.subject(), generated.profile(), NOW)
              instanceof RuleOutcome.Contributed contributed) {
            assertThat(contributed.strength()).as(generated.describe()).isBetween(0.0, 1.0);
          }
        });
  }

  @Test
  @DisplayName("a rule that reports a contribution never contributes nothing")
  void aRuleThatFiresNeverContributesNothing() {
    forEachGeneratedCase(
        generated -> {
          if (rule.evaluate(generated.subject(), generated.profile(), NOW)
              instanceof RuleOutcome.Contributed contributed) {
            assertThat(contributed.strength()).as(generated.describe()).isGreaterThan(0.0);
          }
        });
  }

  @Test
  @DisplayName("evaluating the same inputs twice gives the same answer")
  void evaluationIsDeterministic() {
    forEachGeneratedCase(
        generated ->
            assertThat(rule.evaluate(generated.subject(), generated.profile(), NOW))
                .as(generated.describe())
                .isEqualTo(rule.evaluate(generated.subject(), generated.profile(), NOW)));
  }

  private void forEachGeneratedCase(java.util.function.Consumer<GeneratedCase> assertion) {
    RandomGenerator random = new Random(SEED);
    IntStream.range(0, TRIALS).forEach(trial -> assertion.accept(generate(random, trial)));
  }

  private static GeneratedCase generate(RandomGenerator random, int trial) {
    List<String> keywords = new ArrayList<>();
    IntStream.rangeClosed(0, random.nextInt(5)).forEach(i -> keywords.add(word(random)));
    String objectDescription =
        IntStream.rangeClosed(0, random.nextInt(30))
            .mapToObj(i -> word(random))
            .collect(Collectors.joining(" "));
    return new GeneratedCase(trial, List.copyOf(keywords), objectDescription);
  }

  private static String word(RandomGenerator random) {
    return IntStream.rangeClosed(0, random.nextInt(11))
        .mapToObj(i -> String.valueOf((char) ('a' + random.nextInt(26))))
        .collect(Collectors.joining());
  }

  private record GeneratedCase(int trial, List<String> keywords, String objectDescription) {

    ScoringSubject subject() {
      return new ScoringSubject(aProcurement().describing(objectDescription).build());
    }

    io.github.furlanettoeduardo.radar.domain.profile.SearchProfile profile() {
      return aProfile().withKeywords(keywords.toArray(String[]::new)).build();
    }

    String describe() {
      return "seed=%d trial=%d keywords=%s object=%s"
          .formatted(SEED, trial, keywords, objectDescription);
    }
  }
}
