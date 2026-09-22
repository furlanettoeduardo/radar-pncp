package io.github.furlanettoeduardo.radar.ingestion.pncp;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
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
}
