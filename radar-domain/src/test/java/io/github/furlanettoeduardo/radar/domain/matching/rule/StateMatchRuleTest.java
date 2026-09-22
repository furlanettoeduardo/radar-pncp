package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateMatchRuleTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final StateMatchRule rule = new StateMatchRule();

  @Test
  @DisplayName("contributes full strength when the procurement is in a state the profile wants")
  void contributesWhenTheStateIsWanted() {
    ScoringSubject subject =
        ScoringSubject.unenriched(aProcurement().in(BrazilianState.SP).build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().in(BrazilianState.SP, BrazilianState.MG).build(), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Contributed.class,
            contributed -> {
              assertThat(contributed.strength()).isEqualTo(1.0);
              assertThat(contributed.reason()).contains("SP");
            });
  }

  @Test
  @DisplayName("is silent when the procurement is somewhere the profile did not ask for")
  void isSilentWhenTheStateIsNotWanted() {
    ScoringSubject subject =
        ScoringSubject.unenriched(aProcurement().in(BrazilianState.AM).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().in(BrazilianState.SP).build(), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Silent.class, silent -> assertThat(silent.reason()).contains("AM"));
  }

  @Test
  @DisplayName("geography is all or nothing: there is no partial credit for a neighbouring state")
  void hasNoPartialCredit() {
    ScoringSubject subject =
        ScoringSubject.unenriched(aProcurement().in(BrazilianState.RJ).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().in(BrazilianState.SP).build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("is unavailable when the profile names no states, since it cannot discriminate")
  void isUnavailableWhenTheProfileNamesNoStates() {
    ScoringSubject subject =
        ScoringSubject.unenriched(aProcurement().in(BrazilianState.SP).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().in().build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Unavailable.class);
  }
}
