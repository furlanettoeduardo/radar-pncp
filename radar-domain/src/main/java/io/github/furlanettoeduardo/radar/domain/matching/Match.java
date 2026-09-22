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
}
