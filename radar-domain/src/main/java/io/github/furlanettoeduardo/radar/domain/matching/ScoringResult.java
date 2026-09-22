package io.github.furlanettoeduardo.radar.domain.matching;

import io.github.furlanettoeduardo.radar.domain.common.Score;
import java.util.List;

/**
 * What the engine concluded. Three outcomes, kept apart on purpose.
 *
 * <p>Disqualified is not a very low score. No score is computed at all, and no weighting can undo
 * it. BelowThreshold did score, and says so, because a user tuning a profile needs to see the near
 * misses rather than have them silently discarded.
 */
public sealed interface ScoringResult {

  record Matched(Match match) implements ScoringResult {}

  record Disqualified(RuleId rule, String reason) implements ScoringResult {}

  record BelowThreshold(Score score, Score threshold, List<MatchReason> reasons)
      implements ScoringResult {}
}
