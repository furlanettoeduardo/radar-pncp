package io.github.furlanettoeduardo.radar.domain.profile;

import java.util.Objects;
import java.util.UUID;

/** Identity of a {@code SearchProfile} aggregate. */
public record SearchProfileId(UUID value) {

  public SearchProfileId {
    Objects.requireNonNull(value, "a search profile id must have a value");
  }
}
