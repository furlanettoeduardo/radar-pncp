package io.github.furlanettoeduardo.radar.domain.matching;

import io.github.furlanettoeduardo.radar.domain.common.Score;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfileId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A procurement worth a profile owner looking at, carrying the whole argument for why.
 *
 * <p>The breakdown is ordered by contribution, so the first line is the strongest reason. It keeps
 * the rules that found nothing and the rules that could not run, because the question a user
 * actually asks is not only why a match scored highly, but why it did not score higher.
 */
public record Match(
    MatchId id,
    PncpControlNumber procurement,
    SearchProfileId profile,
    Score score,
    EvidenceCoverage evidenceCoverage,
    List<MatchReason> reasons,
    Instant evaluatedAt) {

  public Match {
    Objects.requireNonNull(id, "a match must have an id");
    Objects.requireNonNull(procurement, "a match must reference a procurement");
    Objects.requireNonNull(profile, "a match must reference a search profile");
    Objects.requireNonNull(score, "a match must carry a score");
    Objects.requireNonNull(evidenceCoverage, "a match must carry its evidence coverage");
    Objects.requireNonNull(reasons, "a match must carry its reasons");
    Objects.requireNonNull(evaluatedAt, "a match must record when it was evaluated");
    if (reasons.isEmpty()) {
      throw new IllegalArgumentException("a match without reasons is not explainable");
    }
    reasons = List.copyOf(reasons);
  }

  /**
   * Whether this score will change on its own.
   *
   * <p>True when some rule is still waiting for an input a worker has yet to produce, which today
   * means an unenriched procurement. Derived from the breakdown rather than stored, so a consumer
   * that does not care can ignore it and lose nothing but an explanation.
   *
   * <p>A hidden budget does not make a match provisional. That input is never arriving.
   */
  public boolean provisional() {
    return reasons.stream().anyMatch(reason -> reason.outcome() instanceof RuleOutcome.Pending);
  }
}
