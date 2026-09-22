package io.github.furlanettoeduardo.radar.domain.port;

import io.github.furlanettoeduardo.radar.domain.enrichment.Enrichment;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Optional;

/**
 * Where an enrichment comes from.
 *
 * <p>Returns an empty optional rather than throwing when no enrichment could be produced. A
 * language model being unavailable, rate limited or unsure is an expected outcome on this path, not
 * an exceptional one, and the scoring engine already has a not applicable branch for it.
 */
public interface EnrichmentProvider {

  Optional<Enrichment> enrich(Procurement procurement);
}
