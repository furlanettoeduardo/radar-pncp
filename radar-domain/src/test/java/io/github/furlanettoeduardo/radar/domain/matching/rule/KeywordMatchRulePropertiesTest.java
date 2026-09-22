package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Invariants that must hold for every input, not just the ones an example test thought of. */
class KeywordMatchRulePropertiesTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final KeywordMatchRule rule = new KeywordMatchRule();

  @Property
  void strengthIsAlwaysWithinTheUnitInterval(
      @ForAll("keywords") List<String> keywords, @ForAll("objects") String objectDescription) {
    RuleOutcome outcome = evaluate(keywords, objectDescription);

    if (outcome instanceof RuleOutcome.Contributed contributed) {
      assertThat(contributed.strength()).isBetween(0.0, 1.0);
    }
  }

  @Property
  void aRuleThatFiresNeverContributesNothing(
      @ForAll("keywords") List<String> keywords, @ForAll("objects") String objectDescription) {
    RuleOutcome outcome = evaluate(keywords, objectDescription);

    if (outcome instanceof RuleOutcome.Contributed contributed) {
      assertThat(contributed.strength()).isGreaterThan(0.0);
    }
  }

  @Property
  void evaluationIsDeterministicForTheSameInputs(
      @ForAll("keywords") List<String> keywords, @ForAll("objects") String objectDescription) {
    assertThat(evaluate(keywords, objectDescription))
        .isEqualTo(evaluate(keywords, objectDescription));
  }

  private RuleOutcome evaluate(List<String> keywords, String objectDescription) {
    ScoringSubject subject =
        new ScoringSubject(aProcurement().describing(objectDescription).build());
    SearchProfile profile = aProfile().withKeywords(keywords.toArray(String[]::new)).build();
    return rule.evaluate(subject, profile, NOW);
  }

  @Provide
  Arbitrary<List<String>> keywords() {
    return Arbitraries.strings()
        .alpha()
        .ofMinLength(2)
        .ofMaxLength(12)
        .list()
        .ofMinSize(1)
        .ofMaxSize(5)
        .uniqueElements();
  }

  @Provide
  Arbitrary<String> objects() {
    return Arbitraries.strings()
        .alpha()
        .withChars(' ')
        .ofMinLength(1)
        .ofMaxLength(200)
        .filter(text -> !text.isBlank());
  }
}
