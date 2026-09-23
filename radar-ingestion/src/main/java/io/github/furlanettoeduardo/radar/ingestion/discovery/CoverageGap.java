package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A publication date that left the lookback window without ever being covered.
 *
 * <p>This is the failure the whole chunk design exists to make visible. Before it, a date that no
 * run ever managed to fetch simply stopped being asked for, and the notices published on it were
 * gone with nothing in the system that knew.
 */
public record CoverageGap(LocalDate publicationDate, int modalityCode, BrazilianState state) {

  public CoverageGap {
    Objects.requireNonNull(publicationDate, "a gap is about a publication date");
    Objects.requireNonNull(state, "a gap is about one state");
  }
}
