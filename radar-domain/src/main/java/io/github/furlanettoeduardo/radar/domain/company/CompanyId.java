package io.github.furlanettoeduardo.radar.domain.company;

import java.util.Objects;
import java.util.UUID;

/** Identity of a {@code Company} aggregate. */
public record CompanyId(UUID value) {

  public CompanyId {
    Objects.requireNonNull(value, "a company id must have a value");
  }
}
