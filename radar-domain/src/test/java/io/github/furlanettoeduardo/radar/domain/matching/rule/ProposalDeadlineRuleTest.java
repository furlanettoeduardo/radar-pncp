package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProposalDeadlineRuleTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
  private static final SearchProfile ANY_PROFILE = aProfile().build();

  private final ProposalDeadlineRule rule = new ProposalDeadlineRule(DeadlineHorizon.standard());

  @Test
  @DisplayName("a closed deadline disqualifies outright, it does not merely score low")
  void aClosedDeadlineDisqualifies() {
    RuleOutcome outcome = evaluateWithDaysLeft(-1);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Disqualified.class,
            disqualified -> assertThat(disqualified.reason()).contains("closed"));
  }

  @Test
  @DisplayName("the closing instant itself is already closed")
  void theClosingInstantIsClosed() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().closingAt(NOW).build());

    assertThat(rule.evaluate(subject, ANY_PROFILE, NOW))
        .isInstanceOf(RuleOutcome.Disqualified.class);
  }

  @Test
  @DisplayName("full strength beyond the comfortable horizon, and at it")
  void fullStrengthBeyondTheComfortableHorizon() {
    assertThat(strengthWithDaysLeft(21)).isCloseTo(1.0, within(1e-9));
    assertThat(strengthWithDaysLeft(14)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  @DisplayName("degrades linearly between the viable and the comfortable horizon")
  void degradesLinearlyInBetween() {
    assertThat(strengthWithDaysLeft(8)).isCloseTo(0.55, within(1e-9));
    assertThat(strengthWithDaysLeft(5)).isCloseTo(0.325, within(1e-9));
  }

  @Test
  @DisplayName("bottoms out at the floor rather than at zero, since a rushed bid is still a bid")
  void bottomsOutAtTheFloor() {
    assertThat(strengthWithDaysLeft(2)).isCloseTo(0.10, within(1e-9));
    assertThat(strengthWithDaysLeft(1)).isCloseTo(0.10, within(1e-9));
  }

  @Test
  @DisplayName("an open deadline is never disqualified, however close it is")
  void anOpenDeadlineIsNeverDisqualified() {
    assertThat(rule.evaluate(subjectWithDaysLeft(1), ANY_PROFILE, NOW))
        .isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("the horizons are configuration, not constants baked into the rule")
  void theHorizonsAreConfiguration() {
    ProposalDeadlineRule impatient =
        new ProposalDeadlineRule(
            new DeadlineHorizon(Duration.ofDays(30), Duration.ofDays(10), 0.25));

    RuleOutcome outcome = impatient.evaluate(subjectWithDaysLeft(10), ANY_PROFILE, NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Contributed.class,
            contributed -> assertThat(contributed.strength()).isCloseTo(0.25, within(1e-9)));
  }

  private double strengthWithDaysLeft(int days) {
    RuleOutcome outcome = evaluateWithDaysLeft(days);
    assertThat(outcome).isInstanceOf(RuleOutcome.Contributed.class);
    return ((RuleOutcome.Contributed) outcome).strength();
  }

  private RuleOutcome evaluateWithDaysLeft(int days) {
    return rule.evaluate(subjectWithDaysLeft(days), ANY_PROFILE, NOW);
  }

  private static ScoringSubject subjectWithDaysLeft(int days) {
    return ScoringSubject.unenriched(
        aProcurement().closingAt(NOW.plus(Duration.ofDays(days))).build());
  }
}
