package io.github.furlanettoeduardo.radar.domain.procurement;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a discovered procurement should be stored.
 *
 * <p>Two rules, both business decisions rather than plumbing, which is why they are here and not in
 * a message listener:
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
 * timestamp could never replace one that has it, no matter how many times it changed.
 *
 * <p>Equal timestamps with different content are stored for the same reason. The source says the
 * two are contemporaneous, the hash says they differ, and only one of those can be acted on.
 */
public final class ProcurementIngestion {

  private final ProcurementRepository repository;

  public ProcurementIngestion(ProcurementRepository repository) {
    this.repository = Objects.requireNonNull(repository, "ingestion needs somewhere to store");
  }

  public IngestionOutcome record(Procurement incoming) {
    Objects.requireNonNull(incoming, "there is nothing to record");

    Optional<Procurement> existing = repository.findByControlNumber(incoming.controlNumber());
    if (existing.isEmpty()) {
      repository.save(incoming);
      return new IngestionOutcome.Stored(incoming);
    }

    Procurement stored = existing.get();
    if (stored.sourcePayloadHash().equals(incoming.sourcePayloadHash())) {
      return new IngestionOutcome.Unchanged(incoming.controlNumber());
    }

    Optional<Instant> storedAt = stored.sourceUpdatedAt();
    Optional<Instant> incomingAt = incoming.sourceUpdatedAt();
    if (storedAt.isPresent()
        && incomingAt.isPresent()
        && incomingAt.get().isBefore(storedAt.get())) {
      return new IngestionOutcome.Stale(storedAt.get(), incomingAt.get());
    }

    repository.save(incoming);
    return new IngestionOutcome.Stored(incoming);
  }
}
