package io.github.furlanettoeduardo.radar.domain.port;

import io.github.furlanettoeduardo.radar.domain.matching.Match;
import io.github.furlanettoeduardo.radar.domain.matching.MatchId;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfileId;
import java.util.List;
import java.util.Optional;

/**
 * Where matches are kept.
 *
 * <p>{@code save} is an upsert by construction: a match id is derived from the procurement and
 * profile it is about, so re-scoring the same pair overwrites rather than accumulating.
 */
public interface MatchRepository {

  void save(Match match);

  Optional<Match> findById(MatchId id);

  List<Match> findByProfile(SearchProfileId profile);
}
