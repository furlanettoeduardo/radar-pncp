package io.github.furlanettoeduardo.radar.domain.matching;

import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfileId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a Match, derived from the pair it is about rather than generated.
 *
 * <p>There is exactly one match per procurement and profile, so scoring the same pair again
 * produces the same identity. That keeps re-scoring idempotent, gives persistence a natural upsert
 * key, and leaves the engine deterministic: the same inputs give the same output, identity
 * included.
 */
public record MatchId(UUID value) {

  public MatchId {
    Objects.requireNonNull(value, "a match id must have a value");
  }

  public static MatchId of(PncpControlNumber procurement, SearchProfileId profile) {
    String seed = procurement.value() + "|" + profile.value();
    return new MatchId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
  }
}
