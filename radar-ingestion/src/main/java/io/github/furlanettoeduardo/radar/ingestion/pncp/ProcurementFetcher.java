package io.github.furlanettoeduardo.radar.ingestion.pncp;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Fetching with the raw payload still attached.
 *
 * <p>{@code ProcurementSource} in the domain returns procurements, which is all the domain needs.
 * Publishing a message needs the bytes PNCP actually sent, and those are an adapter concern, so
 * they never cross the port. This interface exists so the discovery job can be tested without a
 * network, not because the domain asked for it.
 */
public interface ProcurementFetcher {

  List<FetchedProcurement> fetch(ProcurementQuery query);

  /**
   * One chunk: a single publication date, modality and state, paginated to completion.
   *
   * <p>The deadline is absolute and belongs to the whole run rather than to this chunk, so a slow
   * chunk spends the budget its siblings were going to use rather than being given a fresh one.
   */
  ChunkFetch fetchChunk(
      LocalDate publicationDate, int modalityCode, BrazilianState state, Instant deadline);
}
