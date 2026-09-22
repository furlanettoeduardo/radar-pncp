package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.util.Objects;

/**
 * What became of one notice at the boundary.
 *
 * <p>Sealed so that a rejection cannot be mistaken for an absence. A bad record must not sink a
 * good page, and it must not vanish either: every rejection names the notice, the field and the
 * reason, so it can be logged, counted and chased.
 */
public sealed interface MappingResult {

  record Mapped(FetchedProcurement fetched) implements MappingResult {

    public Mapped {
      Objects.requireNonNull(fetched, "a mapped result must carry what was mapped");
    }
  }

  record Rejected(String controlNumber, String field, String reason) implements MappingResult {

    /** Used when the notice is so malformed that even its identity could not be read. */
    public static final String UNKNOWN_NOTICE = "unknown";

    public Rejected {
      Objects.requireNonNull(controlNumber, "a rejection must name the notice, even if unknown");
      Objects.requireNonNull(field, "a rejection must name the field that caused it");
      Objects.requireNonNull(reason, "a rejection must carry a reason");
    }
  }
}
