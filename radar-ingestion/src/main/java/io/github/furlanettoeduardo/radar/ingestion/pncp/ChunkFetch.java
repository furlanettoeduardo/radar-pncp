package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.util.List;
import java.util.Objects;

/**
 * What one chunk fetched.
 *
 * <p>The page count travels with the procurements because the chunk record stores it, and a count
 * derived later from the number of notices would be wrong for every page PNCP returned empty.
 */
public record ChunkFetch(List<FetchedProcurement> procurements, int pagesFetched) {

  public ChunkFetch {
    Objects.requireNonNull(procurements, "a chunk fetch must carry its procurements");
    if (pagesFetched < 0) {
      throw new IllegalArgumentException("a chunk cannot have fetched a negative number of pages");
    }
    procurements = List.copyOf(procurements);
  }
}
