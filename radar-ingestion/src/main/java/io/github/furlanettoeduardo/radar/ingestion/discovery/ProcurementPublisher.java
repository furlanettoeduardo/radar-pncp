package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;

/**
 * Hands a discovered procurement to the queue.
 *
 * <p>Deliberately <em>not</em> a domain port, and the distinction is worth stating because the
 * repository went the other way. A port belongs in the domain when a domain rule depends on it:
 * storing a procurement is governed by idempotency and last-write-wins, which are business
 * decisions, so {@code ProcurementRepository} is a domain port. Publishing is governed by nothing —
 * it is "put this on a queue" — so it stays here, where the queue is.
 */
public interface ProcurementPublisher {

  void publish(ProcurementDiscovered message);
}
