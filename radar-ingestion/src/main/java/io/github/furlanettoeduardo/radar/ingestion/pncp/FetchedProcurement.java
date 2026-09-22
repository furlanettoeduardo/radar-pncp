package io.github.furlanettoeduardo.radar.ingestion.pncp;

import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Objects;

/**
 * One notice as this adapter fetched it: the domain object, and the bytes it came from.
 *
 * <p>The raw payload stays on this side of the port. It is a storage concern, kept so that a later
 * stage can persist exactly what PNCP sent rather than a re-rendering of it.
 *
 * <p>{@code dataAtualizacaoGlobal} used to live here too. It has moved onto {@link Procurement},
 * because deciding whether an incoming notice supersedes the stored one turned out to be a business
 * rule rather than a detail of fetching, and a rule the domain cannot see is a rule the domain
 * cannot enforce.
 */
public record FetchedProcurement(Procurement procurement, String rawPayload) {

  public FetchedProcurement {
    Objects.requireNonNull(procurement, "a fetched procurement must carry a procurement");
    Objects.requireNonNull(rawPayload, "a fetched procurement must carry its raw payload");
  }
}
