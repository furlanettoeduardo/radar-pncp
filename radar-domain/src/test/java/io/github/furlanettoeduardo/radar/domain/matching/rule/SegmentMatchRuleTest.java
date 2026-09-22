package io.github.furlanettoeduardo.radar.domain.matching.rule;

import static io.github.furlanettoeduardo.radar.domain.EnrichmentBuilder.anEnrichment;
import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static io.github.furlanettoeduardo.radar.domain.SearchProfileBuilder.aProfile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.enrichment.ProcurementSegment;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SegmentMatchRuleTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final SegmentMatchRule rule = new SegmentMatchRule();

  @Test
  @DisplayName("contributes at the confidence of the inference, not at full strength")
  void contributesAtTheConfidenceOfTheInference() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement().build(),
            anEnrichment().forSegment(ProcurementSegment.IT_SERVICES).confident(0.80).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().withCnaes("6201-5/01").build(), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.Contributed.class,
            contributed -> {
              assertThat(contributed.strength()).isEqualTo(0.80);
              assertThat(contributed.reason()).contains("IT_SERVICES");
            });
  }

  @Test
  @DisplayName("is silent when the inferred segment is not one the company serves")
  void isSilentWhenTheSegmentIsNotServed() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement().build(),
            anEnrichment().forSegment(ProcurementSegment.CIVIL_WORKS).confident(0.95).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().withCnaes("6201-5/01").build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Silent.class);
  }

  @Test
  @DisplayName("several CNAEs cover several segments")
  void severalCnaesCoverSeveralSegments() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement().build(),
            anEnrichment().forSegment(ProcurementSegment.CIVIL_WORKS).confident(1.0).build());

    RuleOutcome outcome =
        rule.evaluate(subject, aProfile().withCnaes("6201-5/01", "4120-4/00").build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Contributed.class);
  }

  @Test
  @DisplayName("is not applicable when the procurement has not been enriched yet")
  void isNotApplicableWithoutEnrichment() {
    ScoringSubject subject = ScoringSubject.unenriched(aProcurement().build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().withCnaes("6201-5/01").build(), NOW);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RuleOutcome.NotApplicable.class,
            notApplicable -> assertThat(notApplicable.reason()).contains("not been enriched"));
  }

  @Test
  @DisplayName("is not applicable when the profile declares no CNAEs")
  void isNotApplicableWithoutCnaes() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement().build(),
            anEnrichment().forSegment(ProcurementSegment.IT_SERVICES).confident(0.9).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.NotApplicable.class);
  }

  @Test
  @DisplayName("a CNAE division the map does not cover falls back to OTHER rather than vanishing")
  void unmappedDivisionsFallBackToOther() {
    ScoringSubject subject =
        ScoringSubject.enriched(
            aProcurement().build(),
            anEnrichment().forSegment(ProcurementSegment.OTHER).confident(0.5).build());

    RuleOutcome outcome = rule.evaluate(subject, aProfile().withCnaes("9900-8/00").build(), NOW);

    assertThat(outcome).isInstanceOf(RuleOutcome.Contributed.class);
  }
}
