package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * One page as PNCP returned it, still unmapped.
 *
 * <p>Notices stay as parsed JSON here so that the page fetch stays one job. Deciding whether a
 * notice is a procurement belongs to the mapper, which can reject it by name.
 */
public record PncpPage(List<JsonNode> notices, int totalPages, int pageNumber) {

  public PncpPage {
    Objects.requireNonNull(notices, "a page must have a notice list, empty if there are none");
    notices = List.copyOf(notices);
  }

  /** A 204, which PNCP answers with when a query matches nothing. Normal, not a failure. */
  public static PncpPage empty(int pageNumber) {
    return new PncpPage(List.of(), 0, pageNumber);
  }

  public boolean isEmpty() {
    return notices.isEmpty();
  }
}
