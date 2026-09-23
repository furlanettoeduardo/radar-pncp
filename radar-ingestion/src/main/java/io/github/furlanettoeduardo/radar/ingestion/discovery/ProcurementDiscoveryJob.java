package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.ChunkFetch;
import io.github.furlanettoeduardo.radar.ingestion.pncp.FetchedProcurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProperties;
import io.github.furlanettoeduardo.radar.ingestion.pncp.ProcurementFetcher;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One cycle of discovery: report what was lost, plan what is owed, then work it chunk by chunk.
 *
 * <p>A run used to be all or nothing. One failed page discarded every page already fetched, which
 * over three hundred pages against an API measured failing 36 of 42 calls in an afternoon meant a
 * run succeeded exponentially rarely — and it bought nothing, because the consumer deduplicates on
 * content hash, so a partial publish is safe and a repeated chunk is a no-op.
 *
 * <p><b>The ordering of the last two steps of a chunk is the whole design.</b> Every message is
 * published, and only then is the chunk recorded as complete. A crash in between refetches and
 * republishes, which the consumer erases. The reverse order loses notices with nothing in the
 * system that knows they ever existed.
 *
 * <p>The clock is read once per run and once per chunk, and never inside the work. A run that read
 * the clock freely could disagree with itself about what day it is, which at 21:00 in Brasilia is
 * not a hypothetical.
 */
@Component
public final class ProcurementDiscoveryJob {

  private static final Logger LOG = LoggerFactory.getLogger(ProcurementDiscoveryJob.class);
  private static final String EXPIRED_COUNTER = "radar.pncp.chunks.expired";
  private static final String FAILED_COUNTER = "radar.pncp.chunks.failed";

  private final ProcurementFetcher fetcher;
  private final ProcurementPublisher publisher;
  private final DiscoveryChunkRepository chunks;
  private final DiscoveryProperties discovery;
  private final PncpProperties pncp;
  private final MeterRegistry meters;
  private final Clock clock;

  public ProcurementDiscoveryJob(
      ProcurementFetcher fetcher,
      ProcurementPublisher publisher,
      DiscoveryChunkRepository chunks,
      DiscoveryProperties discovery,
      PncpProperties pncp,
      MeterRegistry meters,
      Clock clock) {
    this.fetcher = Objects.requireNonNull(fetcher, "discovery needs something to fetch with");
    this.publisher = Objects.requireNonNull(publisher, "discovery needs somewhere to publish");
    this.chunks = Objects.requireNonNull(chunks, "discovery needs to remember what it has done");
    this.discovery = Objects.requireNonNull(discovery, "discovery needs its properties");
    this.pncp = Objects.requireNonNull(pncp, "discovery needs the PNCP properties");
    this.meters = Objects.requireNonNull(meters, "discovery needs somewhere to count");
    this.clock = Objects.requireNonNull(clock, "discovery needs a clock it can be tested against");
  }

  public DiscoveryReport discover() {
    requireASingleStateScope();

    Instant startedAt = clock.instant();
    DiscoveryCycle cycle = DiscoveryCycle.at(startedAt, discovery.lookbackDays());
    Instant runDeadline = startedAt.plus(pncp.operationDeadline());

    reportCoverageGaps(cycle, startedAt);
    plan(cycle, startedAt);
    return work(cycle, runDeadline);
  }

  /**
   * PNCP takes a single {@code uf}, so an empty state set means asking for the whole country. At
   * the measured volumes that needs an invocation shape this job does not have, and quietly
   * attempting it would be worse than refusing.
   */
  private void requireASingleStateScope() {
    if (discovery.states().isEmpty()) {
      throw new IllegalStateException(
          "radar.discovery.states is empty, which means every state. National scope needs a "
              + "different invocation shape, recorded as a design limit in "
              + "docs/adr/0010-ingestion-runs-daily.md, not a bigger cap.");
    }
  }

  /**
   * A date that left the window without ever being covered is gone, and the one thing that must not
   * happen is for it to go quietly.
   */
  private void reportCoverageGaps(DiscoveryCycle cycle, Instant now) {
    for (CoverageGap gap : chunks.detectGaps(cycle.windowStart(), now)) {
      LOG.error(
          "discovery coverage gap: publicationDate={} modality={} state={} left the {} day window "
              + "with no successful fetch after it closed, and its notices were never collected",
          gap.publicationDate(),
          gap.modalityCode(),
          gap.state(),
          discovery.lookbackDays());
      // Tagged by modality and state, both bounded. The date goes in the message and in the table:
      // a tag whose values grow forever is a slow memory leak on a box with a gigabyte to spend.
      meters
          .counter(
              EXPIRED_COUNTER,
              "modality",
              String.valueOf(gap.modalityCode()),
              "state",
              gap.state().name())
          .increment();
    }
  }

  private void plan(DiscoveryCycle cycle, Instant now) {
    for (LocalDate publicationDate : cycle.windowDates()) {
      for (int modality : pncp.modalityCodes()) {
        for (BrazilianState state : discovery.states()) {
          // Never an upsert: a plan that overwrote would turn a human's backfill back into a
          // scheduled chunk, and the operator would never know their request had been discarded.
          chunks.planIfAbsent(
              DiscoveryChunk.scheduled(cycle.date(), publicationDate, modality, state), now);
        }
      }
    }
  }

  private DiscoveryReport work(DiscoveryCycle cycle, Instant runDeadline) {
    int discovered = 0;
    int published = 0;
    int abandoned = 0;

    List<DiscoveryChunk> owed = chunks.pending(cycle.date());
    for (DiscoveryChunk chunk : owed) {
      Instant now = clock.instant();
      if (!now.isBefore(runDeadline)) {
        // Not a failure, and deliberately not recorded as one. The chunk was never attempted, so
        // counting it against PNCP would make a budget look like an outage.
        abandoned++;
        continue;
      }
      ChunkResult result = workOne(chunk, now, runDeadline);
      discovered += result.discovered();
      published += result.published();
    }

    if (abandoned > 0) {
      LOG.warn(
          "discovery run for {} spent its {} budget with {} chunks still owed; they stay pending "
              + "for the next attempt",
          cycle.date(),
          pncp.operationDeadline(),
          abandoned);
    }
    LOG.info(
        "discovery cycle {} worked {} of {} chunks, found {} procurements and published {}",
        cycle.date(),
        owed.size() - abandoned,
        owed.size(),
        discovered,
        published);
    return new DiscoveryReport(discovered, published);
  }

  private record ChunkResult(int discovered, int published) {
    static final ChunkResult NOTHING = new ChunkResult(0, 0);
  }

  private ChunkResult workOne(DiscoveryChunk chunk, Instant now, Instant runDeadline) {
    chunks.recordAttempt(chunk, now);
    try {
      ChunkFetch fetched =
          fetcher.fetchChunk(
              chunk.publicationDate(), chunk.modalityCode(), chunk.state(), runDeadline);

      int published = 0;
      for (FetchedProcurement procurement : fetched.procurements()) {
        publisher.publish(messageFor(procurement));
        published++;
      }

      // Everything is on the queue. Only now does this chunk count as done.
      chunks.complete(chunk, now, fetched.pagesFetched(), published);
      if (chunk.wouldCover()) {
        chunks.resolveGap(chunk.publicationDate(), chunk.modalityCode(), chunk.state(), now);
      }
      return new ChunkResult(fetched.procurements().size(), published);

    } catch (RuntimeException failure) {
      chunks.recordFailure(chunk, now, failure.toString());
      meters
          .counter(
              FAILED_COUNTER,
              "modality",
              String.valueOf(chunk.modalityCode()),
              "state",
              chunk.state().name())
          .increment();
      if (chunk.origin() == ChunkOrigin.MANUAL) {
        // A backfill nobody is watching retries forever. Said out loud, with its attempt count, so
        // the runbook's health query has something to correlate against.
        LOG.warn(
            "manual backfill chunk {} failed on attempt {}: {}",
            chunk.describe(),
            chunk.attempts() + 1,
            failure.getMessage());
      } else {
        LOG.warn("discovery chunk {} failed: {}", chunk.describe(), failure.getMessage());
      }
      return ChunkResult.NOTHING;
    }
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
