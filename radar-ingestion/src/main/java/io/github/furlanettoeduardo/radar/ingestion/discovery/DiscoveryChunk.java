package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One unit of discovery work: a single PNCP query, paginated to completion.
 *
 * <p>The chunk replaced the run as the unit of success. A run of three hundred pages that discards
 * everything when any one page fails succeeds exponentially rarely against an API measured failing
 * 36 of 42 calls in an afternoon, and it buys nothing: the consumer is idempotent, so a partial
 * publish is safe and a repeated chunk is a no-op.
 *
 * <p>{@code cycleDate} is part of the identity rather than a detail. A publication date keeps
 * gaining notices after it is first fetched, so a chunk that could only ever complete once would
 * see a date at most once and never look again.
 */
public record DiscoveryChunk(
    LocalDate cycleDate,
    LocalDate publicationDate,
    int modalityCode,
    BrazilianState state,
    ChunkOrigin origin,
    int attempts) {

  public DiscoveryChunk {
    Objects.requireNonNull(cycleDate, "a chunk belongs to a cycle");
    Objects.requireNonNull(publicationDate, "a chunk is about a publication date");
    Objects.requireNonNull(state, "a chunk is about one state: PNCP takes a single uf");
    Objects.requireNonNull(origin, "a chunk must say who asked for it");
    if (cycleDate.isBefore(publicationDate)) {
      throw new IllegalArgumentException(
          "a cycle cannot fetch a publication date that has not happened: %s before %s"
              .formatted(cycleDate, publicationDate));
    }
    if (modalityCode <= 0) {
      throw new IllegalArgumentException(
          "a modality code must be positive but was " + modalityCode);
    }
    if (attempts < 0) {
      throw new IllegalArgumentException(
          "a chunk cannot have been attempted a negative number of times");
    }
  }

  public static DiscoveryChunk scheduled(
      LocalDate cycleDate, LocalDate publicationDate, int modalityCode, BrazilianState state) {
    return new DiscoveryChunk(
        cycleDate, publicationDate, modalityCode, state, ChunkOrigin.SCHEDULED, 0);
  }

  /** Whether completing this chunk settles its publication date for good. */
  public boolean wouldCover() {
    return cycleDate.isAfter(publicationDate);
  }

  /** Identity without the mutable parts, for logs and for keying a map. */
  public String describe() {
    return "%s/%s/mod%d/%s".formatted(cycleDate, publicationDate, modalityCode, state);
  }
}
