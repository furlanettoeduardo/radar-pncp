package io.github.furlanettoeduardo.radar.domain.port;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * What to ask a procurement source for.
 *
 * <p>A record rather than positional parameters, so the query can grow a modality, a value floor or
 * a page size without every call site changing shape, and so a reader can tell the two dates apart
 * at the call site.
 *
 * <p>An empty set of states means every state. That is the useful default and the one a caller
 * reaches for; an empty set meaning no results would make the obvious call silently return nothing.
 */
public record ProcurementQuery(
    LocalDate publishedFrom, LocalDate publishedTo, Set<BrazilianState> states) {

  public ProcurementQuery {
    Objects.requireNonNull(publishedFrom, "a query must have a start date");
    Objects.requireNonNull(publishedTo, "a query must have an end date");
    Objects.requireNonNull(states, "states must not be null, use an empty set to mean everywhere");
    if (publishedTo.isBefore(publishedFrom)) {
      throw new IllegalArgumentException(
          "a query range must not end before it starts: %s to %s"
              .formatted(publishedFrom, publishedTo));
    }
    states = Set.copyOf(states);
  }

  public boolean coversEveryState() {
    return states.isEmpty();
  }
}
