package io.github.furlanettoeduardo.radar.ingestion.consumer;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import io.github.furlanettoeduardo.radar.domain.procurement.IngestionOutcome;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.github.furlanettoeduardo.radar.domain.procurement.ProcurementIngestion;
import io.github.furlanettoeduardo.radar.ingestion.pncp.MappingResult;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpJson;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProcurementMapper;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpTimestamps;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Stores what discovery published.
 *
 * <p>The listener owns no rules. It decodes the message, hands the procurement to {@link
 * ProcurementIngestion}, and translates the outcome into logs and counters. Idempotency and
 * last-write-wins live in the domain, in one copy, because a listener that reimplemented either
 * would be a second copy free to drift.
 *
 * <p><b>Failures are not all alike, and SQS cannot tell them apart.</b> A redrive policy counts
 * receives; it has no idea whether a receive failed because the payload is malformed or because
 * Postgres was rebooting. Left alone, a maintenance window sends three receives worth of perfectly
 * good messages to the dead letter queue. Redrive would recover them — the consumer is idempotent —
 * but a routine reboot should not need an operator, so a transient failure extends the message's
 * visibility instead of letting it burn a receive immediately.
 */
@Component
@ConditionalOnProperty(
    prefix = "radar.consumer",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class ProcurementDiscoveredListener {

  private static final Logger LOG = LoggerFactory.getLogger(ProcurementDiscoveredListener.class);

  private static final String OUTCOME_COUNTER = "radar.procurement.ingested";
  private static final String LATE_ARRIVAL_SUMMARY = "radar.pncp.notices.late_arrival";

  private final ProcurementIngestion ingestion;
  private final PncpProcurementMapper mapper;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Duration baseVisibility;

  public ProcurementDiscoveredListener(
      ProcurementIngestion ingestion,
      PncpProcurementMapper mapper,
      MeterRegistry meters,
      Clock clock,
      ConsumerProperties properties) {
    this.ingestion = Objects.requireNonNull(ingestion, "the listener needs the ingestion rule");
    this.mapper = Objects.requireNonNull(mapper, "the listener needs a mapper");
    this.meters = Objects.requireNonNull(meters, "the listener needs somewhere to count");
    this.clock = Objects.requireNonNull(clock, "the listener needs a clock");
    this.baseVisibility = properties.visibilityTimeout();
  }

  @SqsListener("${radar.queues.procurement-discovered}")
  public void onMessage(
      ProcurementDiscovered message,
      Visibility visibility,
      @Header(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT)
          String receiveCount) {
    try {
      consume(message);
    } catch (RuntimeException failure) {
      handle(failure, message, visibility, parseReceiveCount(receiveCount));
      // Rethrown so the message is never acknowledged. Deleting it here would be the one mistake
      // that cannot be undone by any amount of redrive.
      throw failure;
    }
  }

  void consume(ProcurementDiscovered message) {
    if (message.schemaVersion() > ProcurementDiscovered.CURRENT_SCHEMA_VERSION) {
      throw new UnsupportedSchemaVersionException(
          message.schemaVersion(), ProcurementDiscovered.CURRENT_SCHEMA_VERSION);
    }

    MappingResult mapped = mapper.map(readPayload(message));
    switch (mapped) {
      case MappingResult.Mapped ok -> record(ingestion.record(ok.fetched().procurement()));
      case MappingResult.NotBiddable skipped ->
          LOG.info(
              "message carried a notice with no proposal window: control={} reason={}",
              skipped.controlNumber(),
              skipped.reason());
      case MappingResult.Rejected rejected ->
          // Discovery only publishes notices it mapped, so a payload that fails here means the
          // producer and the consumer disagree about the contract. Poison, and loudly so.
          throw new IllegalStateException(
              "a published message carried a payload this consumer cannot map: control=%s field=%s reason=%s"
                  .formatted(rejected.controlNumber(), rejected.field(), rejected.reason()));
    }
  }

  private com.fasterxml.jackson.databind.JsonNode readPayload(ProcurementDiscovered message) {
    try {
      return PncpJson.mapper().readTree(message.rawPayload());
    } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
      throw new IllegalStateException(
          "message " + message.pncpControlNumber() + " carried a payload that is not JSON",
          unreadable);
    }
  }

  private void record(IngestionOutcome outcome) {
    switch (outcome) {
      case IngestionOutcome.Stored stored -> {
        meters.counter(OUTCOME_COUNTER, "outcome", "stored").increment();
        recordLateArrival(stored.procurement());
      }
      case IngestionOutcome.Unchanged unchanged ->
          // Expected rather than exceptional: SQS is at-least-once by design.
          meters.counter(OUTCOME_COUNTER, "outcome", "unchanged").increment();
      case IngestionOutcome.Stale stale -> {
        meters.counter(OUTCOME_COUNTER, "outcome", "stale").increment();
        LOG.warn(
            "discarded an update PNCP says is older than what is stored: stored={} incoming={}",
            stale.storedSourceUpdatedAt(),
            stale.incomingSourceUpdatedAt());
      }
    }
  }

  /**
   * How late a notice was when we first saw it.
   *
   * <p>A distribution rather than a counter, on fixed buckets, because the question it exists to
   * answer is "what share of late arrivals would a lookback of N have caught" — which a count of
   * late arrivals cannot answer and a bucketed distribution answers directly. Fixed buckets rather
   * than a percentile histogram: cheaper in memory on this box, and percentiles are not the
   * question.
   */
  private void recordLateArrival(Procurement procurement) {
    LocalDate publishedOn =
        LocalDate.ofInstant(procurement.publishedAt(), PncpTimestamps.PNCP_ZONE);
    LocalDate today = LocalDate.ofInstant(clock.instant(), PncpTimestamps.PNCP_ZONE);
    long ageInDays = ChronoUnit.DAYS.between(publishedOn, today);

    if (ageInDays < 2) {
      // Today and yesterday are the ordinary case; recording them would bury the signal.
      return;
    }
    DistributionSummary.builder(LATE_ARRIVAL_SUMMARY)
        .description("Age in days of a newly stored notice, when older than yesterday")
        .baseUnit("days")
        .serviceLevelObjectives(1, 2, 3, 4, 5, 7, 14)
        .register(meters)
        .record(ageInDays);
  }

  private void handle(
      RuntimeException failure,
      ProcurementDiscovered message,
      Visibility visibility,
      int receiveCount) {
    if (ConsumerFailures.classify(failure) == FailureKind.TRANSIENT) {
      int backoff = ConsumerFailures.backoffSeconds(receiveCount, baseVisibility);
      // Extending the visibility does not consume another receive; it only delays the next one, so
      // the three receives the redrive policy allows now span an outage rather than 90 seconds.
      visibility.changeTo(backoff);
      LOG.warn(
          "transient failure consuming {} on receive {}; hiding it for {}s rather than spending a "
              + "receive on an outage: {}",
          message.pncpControlNumber(),
          receiveCount,
          backoff,
          failure.getMessage());
      return;
    }
    LOG.error(
        "poison message {} on receive {}; letting the redrive policy take it to the dead letter "
            + "queue: {}",
        message.pncpControlNumber(),
        receiveCount,
        failure.getMessage());
  }

  private static int parseReceiveCount(String header) {
    try {
      return Integer.parseInt(header);
    } catch (NumberFormatException unreadable) {
      // Treat an unreadable count as the first receive: the shortest backoff, never the longest.
      return 1;
    }
  }
}
