package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EstimatedValueRuleTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final EstimatedValueRule rule = new EstimatedValueRule();

  @Test
  @DisplayName("contributes full strength when the estimated value sits inside the profile range")
  void contributesWhenTheValueIsInsideTheRange() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().worth("50000.00").build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().worthBetween("10000", "100000").build(), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Contributed.class,
            contributed -> assertThat(contributed.strength()).isEqualTo(1.0));
  }

  @Test
  @DisplayName("is silent when the contract is too small to be worth pursuing")
  void isSilentBelowTheRange() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().worth("500.00").build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().worthBetween("10000", "100000").build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("is silent when the contract is larger than the company can take on")
  void isSilentAboveTheRange() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().worth("2500000.00").build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().worthBetween("10000", "100000").build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("the range is inclusive at both ends")
  void boundsAreInclusive() {
    assertThat(
            rule.evaluate(
                ScoringSubject.unenriched(aProcurement().worth("10000").build()),
                aProfile().worthBetween("10000", "100000").build(),
                NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
    assertThat(
            rule.evaluate(
                ScoringSubject.unenriched(aProcurement().worth("100000").build()),
                aProfile().worthBetween("10000", "100000").build(),
                NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("compares by numeric value, so scale does not change the answer")
  void comparesNumericallyNotByScale() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().worth("10000").build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().worthBetween("10000.00", "100000.00").build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("is unavailable when PNCP hid the budget, rather than scoring it as zero")
  void isUnavailableWhenTheBudgetIsSecret() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().withSecretBudget().build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().worthBetween("10000", "100000").build(), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Unavailable.class,
            unavailable -> assertThat(unavailable.reason()).contains("no estimated value"));
  }

  @Test
  @DisplayName("is unavailable when the profile declares no value range")
  void isUnavailableWhenTheProfileHasNoRange() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().worth("50000").build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Unavailable.class);
  }
}
