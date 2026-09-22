package io.github.furlanettoeduardo.radar.domain.matching.rule;

import io.github.furlanettoeduardo.radar.domain.company.Cnae;
import io.github.furlanettoeduardo.radar.domain.enrichment.CnaeSegmentMap;
import io.github.furlanettoeduardo.radar.domain.enrichment.Enrichment;
import io.github.furlanettoeduardo.radar.domain.enrichment.ProcurementSegment;
import io.github.furlanettoeduardo.radar.domain.matching.RuleOutcome;
import io.github.furlanettoeduardo.radar.domain.matching.ScoringSubject;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scores whether the procurement is in a line of business the company is actually in.
 *
 * <p>PNCP publishes no classification of what is being bought, so this rule reads the segment a
 * language model inferred from the object, constrained to a closed vocabulary. The company side
 * comes from its own declared CNAEs, mapped through {@link CnaeSegmentMap}.
 *
 * <p>Strength is the confidence of the inference rather than a flat 1.0. A segment guessed at 60%
 * confidence should not weigh the same as one the model was certain of, and carrying a confidence
 * only to ignore it would be worse than not carrying one.
 *
 * <p>Before enrichment arrives the rule is not applicable, which is the honest answer: the
 * criterion was not evaluated, the procurement did not fail it.
 */
public final class SegmentMatchRule implements ScoringRule {

  @Override
  public RuleOutcome evaluate(ScoringSubject subject, SearchProfile profile, Instant evaluatedAt) {
    List<Cnae> cnaes = profile.cnaes();
    if (cnaes.isEmpty()) {
      return new RuleOutcome.NotApplicable(
          "the profile declares no CNAEs, so no segment can be derived for the company");
    }

    Optional<Enrichment> enrichment = subject.enrichment();
    if (enrichment.isEmpty()) {
      return new RuleOutcome.NotApplicable(
          "the procurement has not been enriched, so its segment is unknown");
    }

    Set<ProcurementSegment> served =
        cnaes.stream().map(CnaeSegmentMap::segmentOf).collect(Collectors.toUnmodifiableSet());
    ProcurementSegment inferred = enrichment.get().segment();
    double confidence = enrichment.get().confidence().value();

    if (served.contains(inferred)) {
      return new RuleOutcome.Contributed(
          confidence,
          "inferred segment %s is one the company serves, inferred with %.0f%% confidence"
              .formatted(inferred, confidence * 100));
    }
    return new RuleOutcome.Silent(
        "inferred segment %s is not one the company serves %s".formatted(inferred, served));
  }
}
