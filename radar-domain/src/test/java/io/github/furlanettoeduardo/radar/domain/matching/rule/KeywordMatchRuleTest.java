package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeywordMatchRuleTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final KeywordMatchRule rule = new KeywordMatchRule();

  @Test
  @DisplayName("contributes full strength when every keyword appears in the object description")
  void contributesFullStrengthWhenEveryKeywordAppears() {
    ScoringSubject subject = describing("Aquisicao de brinquedos pedagogicos educativos");

    RuleOutcome outcome = rule.evaluate(subject, withKeywords("brinquedos", "pedagogicos"), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Contributed.class,
            contributed -> {
              assertThat(contributed.strength()).isEqualTo(1.0);
              assertThat(contributed.reason()).contains("2 of 2").contains("brinquedos");
            });
  }

  @Test
  @DisplayName("strength is the fraction of profile keywords found, not a yes or no")
  void contributesPartialStrengthWhenSomeKeywordsAppear() {
    ScoringSubject subject = describing("Aquisicao de brinquedos pedagogicos educativos");

    RuleOutcome outcome =
        rule.evaluate(subject, withKeywords("brinquedos", "uniformes", "merenda", "livros"), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Contributed.class,
            contributed -> assertThat(contributed.strength()).isEqualTo(0.25));
  }

  @Test
  @DisplayName("is silent, not disqualifying, when no keyword appears")
  void isSilentWhenNoKeywordAppears() {
    ScoringSubject subject = describing("Aquisicao de brinquedos pedagogicos educativos");

    RuleOutcome outcome = rule.evaluate(subject, withKeywords("asfalto", "pavimentacao"), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("matches regardless of case")
  void matchesIgnoringCase() {
    ScoringSubject subject = describing("AQUISICAO DE BRINQUEDOS PEDAGOGICOS");

    RuleOutcome outcome = rule.evaluate(subject, withKeywords("brinquedos"), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("matches regardless of accents, in either direction")
  void matchesIgnoringAccents() {
    ScoringSubject accented = describing("AQUISIÇÃO DE BRINQUEDOS PEDAGÓGICOS");
    ScoringSubject plain = describing("AQUISICAO DE BRINQUEDOS PEDAGOGICOS");

    assertThat(rule.evaluate(accented, withKeywords("pedagogicos"), NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
    assertThat(rule.evaluate(plain, withKeywords("pedagógicos"), NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("matches whole words only, so a short keyword does not hit inside a longer word")
  void matchesWholeWordsOnly() {
    ScoringSubject subject = describing("Servico de partida e manutencao de geradores");

    RuleOutcome outcome = rule.evaluate(subject, withKeywords("TI"), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("is unavailable when the profile declares no keywords")
  void isUnavailableWhenProfileHasNoKeywords() {
    ScoringSubject subject = describing("Aquisicao de brinquedos pedagogicos");

    RuleOutcome outcome = rule.evaluate(subject, withKeywords(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Unavailable.class);
  }

  @Test
  @DisplayName("a singular keyword matches the regular plural PNCP actually published")
  void matchesRegularPluralsInTheObject() {
    assertThat(rule.evaluate(describing("Aquisicao de brinquedos"), withKeywords("brinquedo"), NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
    assertThat(
            rule.evaluate(describing("Aquisicao de computadores"), withKeywords("computador"), NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("a plural keyword matches a singular object, so the supplier can type either")
  void matchesWhenTheKeywordIsThePluralOne() {
    assertThat(rule.evaluate(describing("Aquisicao de brinquedo"), withKeywords("brinquedos"), NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
    assertThat(
            rule.evaluate(describing("Aquisicao de computador"), withKeywords("computadores"), NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("plural folding does not turn into a prefix match")
  void pluralFoldingDoesNotBecomeAPrefixMatch() {
    RuleOutcome outcome =
        rule.evaluate(describing("Servico de mao de obra cabocla"), withKeywords("cabo"), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("irregular Portuguese plurals are a known gap, recorded here rather than implied")
  void doesNotHandleIrregularPlurals() {
    RuleOutcome outcome =
        rule.evaluate(
            describing("Aquisicao de materiais escolares"), withKeywords("material"), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  private static ScoringSubject describing(String objectDescription) {
    return ScoringSubject.unenriched(aProcurement().describing(objectDescription).build());
  }

  private static SearchProfile withKeywords(String... keywords) {
    return aProfile().withKeywords(keywords).build();
  }
}
