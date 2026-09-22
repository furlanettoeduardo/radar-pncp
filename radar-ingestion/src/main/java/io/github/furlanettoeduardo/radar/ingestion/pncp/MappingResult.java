package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.util.Objects;

/**
 * What became of one notice at the boundary.
 *
 * <p>Sealed so that three different things cannot be mistaken for one another. A bad record must
 * not sink a good page and must not vanish either, so every rejection names the notice, the field
 * and the reason.
 *
 * <p>{@link NotBiddable} is separate from {@link Rejected} on purpose. A dispensa with no proposal
 * window is not an error, it is what a dispensa is — 9 of the 10 recorded modality 8 notices look
 * like that — and counting it as a rejection would bury a real contract change under routine noise.
 * One is logged at INFO and expected; the other at WARN and worth watching.
 */
public sealed interface MappingResult {

  record Mapped(FetchedProcurement fetched) implements MappingResult {

    public Mapped {
      Objects.requireNonNull(fetched, "a mapped result must carry what was mapped");
    }
  }

  /** A valid notice that nobody can bid on, so it is not an opportunity for our users. */
  record NotBiddable(String controlNumber, String reason) implements MappingResult {

    public NotBiddable {
      Objects.requireNonNull(controlNumber, "a skipped notice must still be named");
      Objects.requireNonNull(reason, "a skipped notice must carry a reason");
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
