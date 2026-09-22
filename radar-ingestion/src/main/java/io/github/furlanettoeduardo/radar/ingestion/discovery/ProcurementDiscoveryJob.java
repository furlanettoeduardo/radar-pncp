package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.FetchedProcurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpTimestamps;
import io.github.furlanettoeduardo.radar.ingestion.pncp.ProcurementFetcher;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds what PNCP published recently and hands each notice to the queue.
 *
 * <p>The evaluation instant is a parameter rather than a call to {@code Instant.now()}, for the
 * same reason the scoring engine takes one: a job that reads the clock cannot be tested against a
 * date boundary, and this one has a date boundary that matters.
 *
 * <p>Not a Spring bean yet: it is wired in the cycle that introduces a publisher, because a bean
 * whose collaborator does not exist stops the context from starting.
 *
 * <p>The window is computed in Brasilia time, not in the JVM default zone. PNCP's {@code
 * dataInicial} and {@code dataFinal} are Brazilian calendar dates, so a container running in UTC
 * would ask for tomorrow for three hours every evening.
 */
public final class ProcurementDiscoveryJob {

  private static final Logger LOG = LoggerFactory.getLogger(ProcurementDiscoveryJob.class);

  private final ProcurementFetcher fetcher;
  private final ProcurementPublisher publisher;
  private final DiscoveryProperties properties;

  public ProcurementDiscoveryJob(
      ProcurementFetcher fetcher, ProcurementPublisher publisher, DiscoveryProperties properties) {
    this.fetcher = Objects.requireNonNull(fetcher, "discovery needs something to fetch with");
    this.publisher = Objects.requireNonNull(publisher, "discovery needs somewhere to publish");
    this.properties = Objects.requireNonNull(properties, "discovery needs its properties");
  }

  public DiscoveryReport discover(Instant now) {
    ProcurementQuery query = windowEndingOn(now);
    List<FetchedProcurement> discovered = fetcher.fetch(query);

    int published = 0;
    for (FetchedProcurement fetched : discovered) {
      publisher.publish(messageFor(fetched));
      published++;
    }

    LOG.info(
        "discovery run {} to {} found {} procurements and published {}",
        query.publishedFrom(),
        query.publishedTo(),
        discovered.size(),
        published);
    return new DiscoveryReport(discovered.size(), published);
  }

  private ProcurementQuery windowEndingOn(Instant now) {
    LocalDate today = LocalDate.ofInstant(now, PncpTimestamps.PNCP_ZONE);
    return new ProcurementQuery(
        today.minusDays(properties.lookbackDays()), today, properties.states());
  }

  private static ProcurementDiscovered messageFor(FetchedProcurement fetched) {
    Procurement procurement = fetched.procurement();
    return ProcurementDiscovered.of(
        procurement.controlNumber().value(),
        procurement.sourcePayloadHash(),
        // Instant.toString is the canonical form the contract requires; it validates this again.
        procurement.sourceUpdatedAt().map(Instant::toString).orElse(null),
        fetched.rawPayload());
  }
}
