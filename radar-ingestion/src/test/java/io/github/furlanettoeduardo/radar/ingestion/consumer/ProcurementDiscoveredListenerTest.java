package io.github.furlanettoeduardo.radar.ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.awspring.cloud.sqs.listener.Visibility;
import io.github.furlanettoeduardo.radar.domain.port.InMemoryProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.github.furlanettoeduardo.radar.domain.procurement.ProcurementIngestion;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpJson;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProcurementMapper;
import io.github.furlanettoeduardo.radar.ingestion.pncp.SampleFixtures;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * The consumer's behaviour, without a queue or a database.
 *
 * <p>What is worth testing here is not that a procurement gets stored — the domain and the adapter
 * contract already cover that — but what the listener does with a failure, because that is the
 * decision SQS cannot make for itself.
 */
class ProcurementDiscoveredListenerTest {

  /** 2026-09-24 09:00 in Sao Paulo. The recorded sample was published on 2026-09-01. */
  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

  private final InMemoryProcurementRepository procurements = new InMemoryProcurementRepository();
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final RecordingVisibility visibility = new RecordingVisibility();

  private ProcurementDiscoveredListener listener() {
    return listener(procurements);
  }

  private ProcurementDiscoveredListener listener(ProcurementRepository repository) {
    return new ProcurementDiscoveredListener(
        new ProcurementIngestion(repository),
        new PncpProcurementMapper(),
        meters,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new ConsumerProperties(Duration.ofSeconds(30), 3));
  }

  private static ProcurementDiscovered message() {
    JsonNode notice = firstRecordedNotice();
    return ProcurementDiscovered.of(
        "44935278000126-1-000343/2025",
        "hash-of-the-payload",
        "2026-09-01T17:22:01Z",
        notice.toString());
  }

  private static JsonNode firstRecordedNotice() {
    try {
      return PncpJson.mapper()
          .readTree(SampleFixtures.read("contratacoes-proposta.json"))
          .get("data")
          .get(0);
    } catch (Exception cause) {
      throw new IllegalStateException("could not read the recorded sample", cause);
    }
  }

  @Test
  @DisplayName("a message is decoded and its procurement stored")
  void aMessageIsStored() {
    listener().onMessage(message(), visibility, "1");

    assertThat(
            procurements.findByControlNumber(new PncpControlNumber("44935278000126-1-000343/2025")))
        .isPresent();
    assertThat(meters.counter("radar.procurement.ingested", "outcome", "stored").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a duplicate delivery stores nothing again: SQS is at-least-once by design")
  void aDuplicateIsUnchanged() {
    listener().onMessage(message(), visibility, "1");
    listener().onMessage(message(), visibility, "2");

    assertThat(procurements.writes()).isEqualTo(1);
    assertThat(meters.counter("radar.procurement.ingested", "outcome", "unchanged").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a notice older than yesterday is recorded in the late arrival distribution")
  void aLateArrivalIsRecorded() {
    listener().onMessage(message(), visibility, "1");

    assertThat(meters.find("radar.pncp.notices.late_arrival").summary())
        .isNotNull()
        .satisfies(
            summary -> {
              assertThat(summary.count()).isEqualTo(1);
              assertThat(summary.max()).isEqualTo(23.0);
            });
  }

  @Test
  @DisplayName("a message from a newer producer is poison and its visibility is not extended")
  void aNewerSchemaVersionIsPoison() {
    ProcurementDiscovered fromTheFuture =
        new ProcurementDiscovered(
            ProcurementDiscovered.CURRENT_SCHEMA_VERSION + 1,
            "44935278000126-1-000343/2025",
            "hash",
            "2026-09-01T17:22:01Z",
            firstRecordedNotice().toString());

    assertThatThrownBy(() -> listener().onMessage(fromTheFuture, visibility, "1"))
        .isInstanceOf(UnsupportedSchemaVersionException.class);

    assertThat(visibility.changedTo)
        .as("poison must reach the dead letter queue quickly, where somebody sees it")
        .isEmpty();
  }

  @Test
  @DisplayName("a database outage extends the visibility instead of spending a receive")
  void aTransientFailureExtendsVisibility() {
    ProcurementDiscoveredListener listener = listener(new DatabaseIsDown());

    assertThatThrownBy(() -> listener.onMessage(message(), visibility, "2"))
        .isInstanceOf(CannotGetJdbcConnectionException.class);

    assertThat(visibility.changedTo)
        .as("second receive, so the second step of the backoff: 30s x 4")
        .containsExactly(120);
  }

  @Test
  @DisplayName("the failure is rethrown, so the message is never acknowledged")
  void theMessageIsNeverAcknowledged() {
    ProcurementDiscoveredListener listener = listener(new DatabaseIsDown());

    assertThatThrownBy(() -> listener.onMessage(message(), visibility, "1"))
        .as("swallowing it would delete the message, which no amount of redrive can undo")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("an unreadable receive count backs off the least, never the most")
  void anUnreadableReceiveCountIsTreatedAsTheFirst() {
    ProcurementDiscoveredListener listener = listener(new DatabaseIsDown());

    assertThatThrownBy(() -> listener.onMessage(message(), visibility, "not a number"))
        .isInstanceOf(CannotGetJdbcConnectionException.class);

    assertThat(visibility.changedTo).containsExactly(30);
  }

  private static final class DatabaseIsDown implements ProcurementRepository {

    @Override
    public Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber) {
      throw new CannotGetJdbcConnectionException("connection refused");
    }

    @Override
    public boolean insertIfAbsent(Procurement procurement) {
      throw new CannotGetJdbcConnectionException("connection refused");
    }

    @Override
    public boolean replaceIfUnchanged(Procurement procurement, String expectedSourcePayloadHash) {
      throw new CannotGetJdbcConnectionException("connection refused");
    }
  }

  private static final class RecordingVisibility implements Visibility {
    private final java.util.List<Integer> changedTo = new java.util.ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public CompletableFuture<Void> changeToAsync(int seconds) {
      calls.incrementAndGet();
      changedTo.add(seconds);
      return CompletableFuture.completedFuture(null);
    }
  }
}
