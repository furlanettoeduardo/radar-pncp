package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static io.github.furlanettoeduardo.radar.ingestion.discovery.ProcurementDiscoveryJobTest.Fakes.fetched;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import io.github.furlanettoeduardo.radar.domain.procurement.Modality;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.FetchedProcurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.ProcurementFetcher;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcurementDiscoveryJobTest {

  /** 2026-09-22 09:00 in Sao Paulo, which is the date PNCP would call it. */
  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  private final RecordingPublisher published = new RecordingPublisher();
  private final StubFetcher fetcher = new StubFetcher();

  private ProcurementDiscoveryJob jobLookingBack(int days, BrazilianState... states) {
    return new ProcurementDiscoveryJob(
        fetcher, published, new DiscoveryProperties(days, Set.of(states)));
  }

  @Test
  @DisplayName("publishes one message per discovered procurement")
  void publishesOneMessagePerProcurement() {
    fetcher.returns(fetched("a", "hash-a"), fetched("b", "hash-b"), fetched("c", "hash-c"));

    DiscoveryReport report = jobLookingBack(2).discover(NOW);

    assertThat(published.messages).hasSize(3);
    assertThat(report.discovered()).isEqualTo(3);
    assertThat(report.published()).isEqualTo(3);
  }

  @Test
  @DisplayName("the message carries what the consumer needs and nothing it has to fetch again")
  void theMessageCarriesTheProcurement() {
    fetcher.returns(fetched("44935278000126-1-000343/2025", "hash-a"));

    jobLookingBack(2).discover(NOW);

    ProcurementDiscovered message = published.messages.get(0);
    assertThat(message.schemaVersion()).isEqualTo(ProcurementDiscovered.CURRENT_SCHEMA_VERSION);
    assertThat(message.pncpControlNumber()).isEqualTo("44935278000126-1-000343/2025");
    assertThat(message.contentHash()).isEqualTo("hash-a");
    assertThat(message.sourceUpdatedAt()).isEqualTo("2026-09-01T17:22:01Z");
    assertThat(message.rawPayload()).contains("numeroControlePNCP");
  }

  @Test
  @DisplayName("a procurement PNCP never dated publishes a message with no timestamp")
  void anUndatedProcurementPublishesWithoutATimestamp() {
    fetcher.returns(Fakes.undated("a", "hash-a"));

    jobLookingBack(2).discover(NOW);

    assertThat(published.messages.get(0).sourceUpdatedAt()).isNull();
  }

  @Test
  @DisplayName("a quiet day publishes nothing rather than an empty message")
  void aQuietDayPublishesNothing() {
    fetcher.returns();

    DiscoveryReport report = jobLookingBack(2).discover(NOW);

    assertThat(published.messages).isEmpty();
    assertThat(report.discovered()).isZero();
  }

  @Test
  @DisplayName("the window ends on today in Brasilia time, not in the JVM default zone")
  void theWindowEndsOnTheBrazilianDate() {
    jobLookingBack(2).discover(NOW);

    assertThat(fetcher.lastQuery.publishedTo()).isEqualTo(LocalDate.of(2026, 9, 22));
    assertThat(fetcher.lastQuery.publishedFrom()).isEqualTo(LocalDate.of(2026, 9, 20));
  }

  @Test
  @DisplayName("an instant late in the UTC day is still the Brazilian day before")
  void aLateUtcInstantIsThePreviousBrazilianDay() {
    jobLookingBack(1).discover(Instant.parse("2026-09-23T02:00:00Z"));

    assertThat(fetcher.lastQuery.publishedTo()).isEqualTo(LocalDate.of(2026, 9, 22));
  }

  @Test
  @DisplayName("no configured states means every state, which the query already expresses")
  void noConfiguredStatesMeansEverywhere() {
    jobLookingBack(2).discover(NOW);

    assertThat(fetcher.lastQuery.coversEveryState()).isTrue();
  }

  @Test
  @DisplayName("configured states are passed through to the query")
  void configuredStatesArePassedThrough() {
    jobLookingBack(2, BrazilianState.SP, BrazilianState.MG).discover(NOW);

    assertThat(fetcher.lastQuery.states())
        .containsExactlyInAnyOrder(BrazilianState.SP, BrazilianState.MG);
  }

  private static final class RecordingPublisher implements ProcurementPublisher {
    private final List<ProcurementDiscovered> messages = new ArrayList<>();

    @Override
    public void publish(ProcurementDiscovered message) {
      messages.add(message);
    }
  }

  private static final class StubFetcher implements ProcurementFetcher {
    private List<FetchedProcurement> results = List.of();
    private ProcurementQuery lastQuery;

    void returns(FetchedProcurement... results) {
      this.results = List.of(results);
    }

    @Override
    public List<FetchedProcurement> fetch(ProcurementQuery query) {
      this.lastQuery = query;
      return results;
    }
  }

  static final class Fakes {

    private static final Instant UPDATED = Instant.parse("2026-09-01T17:22:01Z");

    static FetchedProcurement fetched(String controlNumber, String hash) {
      return new FetchedProcurement(
          procurement(controlNumber, hash, Optional.of(UPDATED)),
          "{\"numeroControlePNCP\":\"" + controlNumber + "\"}");
    }

    static FetchedProcurement undated(String controlNumber, String hash) {
      return new FetchedProcurement(
          procurement(controlNumber, hash, Optional.empty()),
          "{\"numeroControlePNCP\":\"" + controlNumber + "\"}");
    }

    private static Procurement procurement(
        String controlNumber, String hash, Optional<Instant> updatedAt) {
      return new Procurement(
          new PncpControlNumber(controlNumber),
          "Aquisicao de brinquedos pedagogicos",
          BrazilianState.SP,
          Optional.of(MonetaryValue.of("14785.32")),
          Modality.of(6, "Pregao - Eletronico"),
          Instant.parse("2026-09-01T17:20:41Z"),
          Instant.parse("2026-09-02T11:00:00Z"),
          Instant.parse("2026-09-21T20:30:00Z"),
          hash,
          updatedAt);
    }
  }
}
