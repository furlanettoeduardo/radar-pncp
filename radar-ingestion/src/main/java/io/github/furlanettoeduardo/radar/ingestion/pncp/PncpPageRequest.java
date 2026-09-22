package io.github.furlanettoeduardo.radar.ingestion.pncp;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of one modality in one state. PNCP takes a single {@code uf}, so several states mean
 * several requests; an empty state means every state, and the parameter is left off.
 */
public record PncpPageRequest(
    LocalDate publishedFrom,
    LocalDate publishedTo,
    Optional<BrazilianState> state,
    int modalityCode,
    int page) {

  public PncpPageRequest {
    Objects.requireNonNull(publishedFrom, "a page request must have a start date");
    Objects.requireNonNull(publishedTo, "a page request must have an end date");
    Objects.requireNonNull(state, "use Optional.empty() to mean every state");
    if (publishedTo.isBefore(publishedFrom)) {
      throw new IllegalArgumentException("a page request must not end before it starts");
    }
    if (page < 1) {
      throw new IllegalArgumentException("PNCP pages are one based but was " + page);
    }
  }
}
