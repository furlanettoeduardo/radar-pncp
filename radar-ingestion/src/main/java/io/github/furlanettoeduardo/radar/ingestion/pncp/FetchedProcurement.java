package io.github.furlanettoeduardo.radar.ingestion.pncp;

import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One notice as this adapter fetched it: the domain object, the bytes it came from, and the cheap
 * change detector PNCP publishes alongside it.
 *
 * <p>The raw payload and {@code dataAtualizacaoGlobal} are storage and scheduling concerns, so they
 * stay on this side of the port. {@link
 * io.github.furlanettoeduardo.radar.domain.port.ProcurementSource} hands the domain the procurement
 * alone.
 *
 * <p>{@code sourceUpdatedAt} is optional because it is a hint. Losing it costs one redundant hash
 * computation; rejecting an otherwise valid notice over it would cost a real opportunity.
 */
public record FetchedProcurement(
    Procurement procurement, String rawPayload, Optional<Instant> sourceUpdatedAt) {

  public FetchedProcurement {
    Objects.requireNonNull(procurement, "a fetched procurement must carry a procurement");
    Objects.requireNonNull(rawPayload, "a fetched procurement must carry its raw payload");
    Objects.requireNonNull(sourceUpdatedAt, "use Optional.empty() rather than null");
  }
}
