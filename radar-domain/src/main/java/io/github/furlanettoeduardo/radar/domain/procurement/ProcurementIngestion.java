package io.github.furlanettoeduardo.radar.domain.procurement;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a discovered procurement should be stored.
 *
 * <p>Two rules, both business decisions rather than plumbing, which is why they are here and not in
 * a message listener or in SQL:
 *
 * <ul>
 *   <li><b>Idempotency by content.</b> The same content hash is not stored twice. SQS is
 *       at-least-once, so a duplicate delivery is a certainty rather than a fault, and the right
 *       response is to do nothing quietly.
 *   <li><b>Last write wins by source, not by arrival.</b> SQS is unordered, so arrival order says
 *       nothing about which version is newer. PNCP publishes {@code dataAtualizacaoGlobal}, and
 *       that is the only usable notion of newer. An older one is rejected however it arrived.
 * </ul>
 *
 * <p>When either side has no source timestamp, the comparison cannot be made and the content hash
 * decides. Refusing instead would strand an update permanently: a notice PNCP published without a
 * timestamp could never replace one that has it, no matter how many times it changed. Equal
 * timestamps with different content are stored for the same reason, so the only rejection is a
 * strictly older incoming timestamp.
 *
 * <h2>Two consumers deciding at once</h2>
 *
 * <p>Reading, deciding and writing is check-then-act, and two consumers will race it. The fix is
 * not to move the rule into the database — that would leave two copies of it, free to drift — but
 * to make the write conditional and retry the whole decision when it loses.
 *
 * <p>Each write says "only if nothing has changed since I read". If something has, the decision was
 * made against a state that no longer exists, so it is made again from the state that does. After
 * {@link #MAX_ATTEMPTS} the attempt is abandoned to the caller: this runs under a queue consumer,
 * and an unacknowledged message is redelivered by machinery that already has backoff, a receive
 * count and a dead letter queue. Looping here would reimplement all three inside a listener thread.
 */
public final class ProcurementIngestion {

  /**
   * Three, because losing twice in a row is already surprising at the expected volumes. A higher
   * number would hide contention rather than surface it.
   */
  static final int MAX_ATTEMPTS = 3;

  private final ProcurementRepository repository;

  public ProcurementIngestion(ProcurementRepository repository) {
    this.repository = Objects.requireNonNull(repository, "ingestion needs somewhere to store");
  }

  public IngestionOutcome record(Procurement incoming) {
    Objects.requireNonNull(incoming, "there is nothing to record");

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      Optional<Procurement> existing = repository.findByControlNumber(incoming.controlNumber());

      if (existing.isEmpty()) {
        if (repository.insertIfAbsent(incoming)) {
          return new IngestionOutcome.Stored(incoming);
        }
        // Somebody inserted between our read and our write. Decide again against what they wrote.
        continue;
      }

      Procurement stored = existing.get();
      if (stored.sourcePayloadHash().equals(incoming.sourcePayloadHash())) {
        return new IngestionOutcome.Unchanged(incoming.controlNumber());
      }
      if (isStale(incoming, stored)) {
        return new IngestionOutcome.Stale(
            stored.sourceUpdatedAt().orElseThrow(), incoming.sourceUpdatedAt().orElseThrow());
      }
      if (repository.replaceIfUnchanged(incoming, stored.sourcePayloadHash())) {
        return new IngestionOutcome.Stored(incoming);
      }
      // The row moved under us. Our decision was about a state that no longer exists.
    }

    throw new ProcurementContentionException(incoming.controlNumber(), MAX_ATTEMPTS);
  }

  private static boolean isStale(Procurement incoming, Procurement stored) {
    Optional<Instant> storedAt = stored.sourceUpdatedAt();
    Optional<Instant> incomingAt = incoming.sourceUpdatedAt();
    return storedAt.isPresent()
        && incomingAt.isPresent()
        && incomingAt.get().isBefore(storedAt.get());
  }
}
