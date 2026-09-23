package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.procurement.Modality;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.ChunkFetch;
import io.github.furlanettoeduardo.radar.ingestion.pncp.FetchedProcurement;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProperties;
import io.github.furlanettoeduardo.radar.ingestion.pncp.ProcurementFetcher;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One cycle of discovery: report what was lost, plan what is owed, work it chunk by chunk.
 *
 * <p>The ordering test is the one that matters. Recording a chunk as complete before its messages
 * are on the queue would lose notices with nothing in the system knowing; recording it afterwards
 * can at worst republish, which the consumer erases. Every other test here would be caught the
 * first time somebody looked at the logs.
 */
class ProcurementDiscoveryJobTest {

  /** 09:00 on 2026-09-24 in Sao Paulo, which is the date PNCP would call it. */
  private static final Instant NOON_UTC = Instant.parse("2026-09-24T12:00:00Z");

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
  private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 23);
  private static final LocalDate OLDEST = LocalDate.of(2026, 9, 21);

  private final InMemoryDiscoveryChunkRepository chunks = new InMemoryDiscoveryChunkRepository();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final StubFetcher fetcher = new StubFetcher();
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final List<String> order = new ArrayList<>();

  private ProcurementDiscoveryJob job(int... modalities) {
    return job(Clock.fixed(NOON_UTC, ZoneOffset.UTC), Set.of(BrazilianState.SP), modalities);
  }

  private ProcurementDiscoveryJob job(Clock clock, Set<BrazilianState> states, int... modalities) {
    return new ProcurementDiscoveryJob(
        fetcher,
        publisher,
        new RecordingChunks(chunks, order),
        new DiscoveryProperties(3, states),
        pncpWith(modalities),
        meters,
        clock);
  }

  private static PncpProperties pncpWith(int... modalities) {
    List<Integer> codes = new ArrayList<>();
    for (int modality : modalities) {
      codes.add(modality);
    }
    return new PncpProperties(
        "http://localhost",
        codes.isEmpty() ? List.of(6) : codes,
        10,
        500,
        8,
        Duration.ofSeconds(2),
        Duration.ofSeconds(10),
        Duration.ofMinutes(5),
        "radar-pncp/0.1.0");
  }

  @Test
  @DisplayName("plans one chunk per publication date, modality and state in the window")
  void plansEveryCombinationInTheWindow() {
    job(6, 8).discover();

    assertThat(chunks.pending(TODAY))
        .as("a lookback of 3 is four dates, and two modalities in one state is eight chunks")
        .isEmpty();
    assertThat(fetcher.asked)
        .hasSize(8)
        .contains("2026-09-21/6", "2026-09-21/8", "2026-09-24/6", "2026-09-24/8");
  }

  @Test
  @DisplayName("a chunk is completed only after every one of its messages is published")
  void completionComesAfterPublishing() {
    fetcher.returnsPerChunk(fetched("a"), fetched("b"), fetched("c"));

    job(6).discover();

    assertThat(order)
        .as("a crash before completion republishes, which the consumer erases; the reverse loses")
        .startsWith("publish:a", "publish:b", "publish:c", "complete:2026-09-21/6");
  }

  @Test
  @DisplayName("a publish failure part way through leaves the chunk incomplete")
  void aPublishFailureLeavesTheChunkPending() {
    fetcher.returnsPerChunk(fetched("a"), fetched("b"));
    publisher.failOn("b");

    job(6).discover();

    assertThat(chunks.pending(TODAY))
        .as("every chunk failed the same way, so all four are still owed")
        .hasSize(4);
    assertThat(order).contains("publish:a").doesNotContain("complete:2026-09-21/6");
  }

  @Test
  @DisplayName("a fetch failure publishes nothing and leaves the chunk incomplete")
  void aFetchFailuredPublishesNothing() {
    fetcher.failEverything();

    DiscoveryReport report = job(6).discover();

    assertThat(publisher.messages).isEmpty();
    assertThat(report.published()).isZero();
    assertThat(chunks.pending(TODAY)).hasSize(4);
  }

  @Test
  @DisplayName("one chunk failing does not stop the rest: that was the point of chunking")
  void oneFailedChunkDoesNotStopTheOthers() {
    fetcher.returnsPerChunk(fetched("x"));
    fetcher.failFor(YESTERDAY);

    job(6).discover();

    assertThat(chunks.pending(TODAY))
        .as("only the failed date is still owed")
        .singleElement()
        .extracting(DiscoveryChunk::publicationDate)
        .isEqualTo(YESTERDAY);
  }

  @Test
  @DisplayName("chunks are worked oldest publication date first")
  void chunksAreWorkedOldestFirst() {
    fetcher.returnsPerChunk(fetched("x"));

    job(6).discover();

    assertThat(fetcher.asked)
        .as("the oldest date has the fewest covering cycles left")
        .containsExactly("2026-09-21/6", "2026-09-22/6", "2026-09-23/6", "2026-09-24/6");
  }

  @Test
  @DisplayName("a chunk already completed this cycle is not fetched again")
  void aCompletedChunkIsNotRefetched() {
    fetcher.returnsPerChunk(fetched("x"));
    job(6).discover();
    fetcher.asked.clear();

    job(6).discover();

    assertThat(fetcher.asked).isEmpty();
  }

  @Test
  @DisplayName("today is worked even though finishing it cannot settle the date")
  void todayIsWorkedEvenThoughItCannotCover() {
    fetcher.returnsPerChunk(fetched("x"));

    job(6).discover();

    assertThat(fetcher.asked).contains("2026-09-24/6");
  }

  @Test
  @DisplayName("a spent run budget leaves the rest pending, without recording a failure")
  void aSpentBudgetLeavesChunksPendingWithoutFailing() {
    fetcher.returnsPerChunk(fetched("x"));
    // Every reading of the clock advances two minutes, so the five minute budget runs out partway.
    Clock ticking = new TickingClock(NOON_UTC, Duration.ofMinutes(2));

    job(ticking, Set.of(BrazilianState.SP), 6).discover();

    List<DiscoveryChunk> left = chunks.pending(TODAY);
    assertThat(left).as("the budget stopped the run early").isNotEmpty();
    assertThat(left)
        .as("a deadline must never manufacture a failure that looks like PNCP's")
        .allSatisfy(chunk -> assertThat(chunk.attempts()).isZero());
  }

  @Test
  @DisplayName("a manual backfill outside the window is worked like anything else")
  void aManualChunkOutsideTheWindowIsWorked() {
    fetcher.returnsPerChunk(fetched("x"));
    chunks.planIfAbsent(
        new DiscoveryChunk(
            LocalDate.of(2026, 9, 24),
            LocalDate.of(2026, 5, 4),
            6,
            BrazilianState.SP,
            ChunkOrigin.MANUAL,
            0),
        NOON_UTC);

    job(6).discover();

    assertThat(fetcher.asked).contains("2026-05-04/6");
  }

  @Test
  @DisplayName("a publication date that left the window uncovered is reported once, loudly")
  void aLostDateIsReportedWithAMetric() {
    // A cycle ran on the 20th and only managed the 20th itself, which cannot count as coverage.
    DiscoveryChunk sameDayOnly =
        DiscoveryChunk.scheduled(
            LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 20), 6, BrazilianState.SP);
    chunks.planIfAbsent(sameDayOnly, NOON_UTC);
    chunks.complete(sameDayOnly, NOON_UTC, 1, 1);
    fetcher.returnsPerChunk(fetched("x"));

    job(6).discover();

    assertThat(meters.counter("radar.pncp.chunks.expired", "modality", "6", "state", "SP").count())
        .isEqualTo(1.0);
    assertThat(chunks.openGaps())
        .singleElement()
        .extracting(CoverageGap::publicationDate)
        .isEqualTo(LocalDate.of(2026, 9, 20));
  }

  @Test
  @DisplayName("national scope is refused rather than attempted, naming the ADR")
  void nationalScopeIsRefused() {
    assertThatThrownBy(() -> job(Clock.fixed(NOON_UTC, ZoneOffset.UTC), Set.of(), 6).discover())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0010");
  }

  // ---------------------------------------------------------------- fakes

  private static FetchedProcurement fetched(String controlNumber) {
    Procurement procurement =
        new Procurement(
            new PncpControlNumber(controlNumber),
            "Aquisicao de brinquedos pedagogicos",
            BrazilianState.SP,
            Optional.of(MonetaryValue.of("1000.00")),
            Modality.of(6, "Pregao - Eletronico"),
            Instant.parse("2026-09-01T17:20:41Z"),
            Instant.parse("2026-09-02T11:00:00Z"),
            Instant.parse("2026-09-21T20:30:00Z"),
            "hash-" + controlNumber,
            Optional.of(Instant.parse("2026-09-01T17:22:01Z")));
    return new FetchedProcurement(
        procurement, "{\"numeroControlePNCP\":\"" + controlNumber + "\"}");
  }

  /** Advances every time it is read, so a budget can run out without a real wait. */
  private static final class TickingClock extends Clock {
    private Instant now;
    private final Duration step;

    TickingClock(Instant start, Duration step) {
      this.now = start;
      this.step = step;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      Instant current = now;
      now = now.plus(step);
      return current;
    }
  }

  private static final class StubFetcher implements ProcurementFetcher {
    private final List<String> asked = new ArrayList<>();
    private List<FetchedProcurement> perChunk = List.of();
    private boolean failEverything;
    private LocalDate failDate;

    void returnsPerChunk(FetchedProcurement... procurements) {
      this.perChunk = List.of(procurements);
    }

    void failEverything() {
      this.failEverything = true;
    }

    void failFor(LocalDate date) {
      this.failDate = date;
    }

    @Override
    public List<FetchedProcurement> fetch(
        io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery query) {
      throw new UnsupportedOperationException("the job fetches by chunk");
    }

    @Override
    public ChunkFetch fetchChunk(
        LocalDate publicationDate, int modalityCode, BrazilianState state, Instant deadline) {
      asked.add(publicationDate + "/" + modalityCode);
      if (failEverything || publicationDate.equals(failDate)) {
        throw new IllegalStateException("PNCP is having one of its afternoons");
      }
      return new ChunkFetch(perChunk, 1);
    }
  }

  private static final class RecordingPublisher implements ProcurementPublisher {
    private final List<ProcurementDiscovered> messages = new ArrayList<>();
    private String failOn;
    private List<String> order;

    void failOn(String controlNumber) {
      this.failOn = controlNumber;
    }

    @Override
    public void publish(ProcurementDiscovered message) {
      if (message.pncpControlNumber().equals(failOn)) {
        throw new IllegalStateException("SQS said no");
      }
      messages.add(message);
      if (order != null) {
        order.add("publish:" + message.pncpControlNumber());
      }
    }
  }

  /** Records completion against the same list the publisher writes to, so order is observable. */
  private final class RecordingChunks implements DiscoveryChunkRepository {
    private final DiscoveryChunkRepository delegate;

    RecordingChunks(DiscoveryChunkRepository delegate, List<String> order) {
      this.delegate = delegate;
      publisher.order = order;
    }

    @Override
    public boolean planIfAbsent(DiscoveryChunk chunk, Instant now) {
      return delegate.planIfAbsent(chunk, now);
    }

    @Override
    public List<DiscoveryChunk> pending(LocalDate cycleDate) {
      return delegate.pending(cycleDate);
    }

    @Override
    public void recordAttempt(DiscoveryChunk chunk, Instant now) {
      delegate.recordAttempt(chunk, now);
    }

    @Override
    public boolean complete(
        DiscoveryChunk chunk, Instant now, int pagesFetched, int noticesPublished) {
      order.add("complete:" + chunk.publicationDate() + "/" + chunk.modalityCode());
      return delegate.complete(chunk, now, pagesFetched, noticesPublished);
    }

    @Override
    public void recordFailure(DiscoveryChunk chunk, Instant now, String reason) {
      delegate.recordFailure(chunk, now, reason);
    }

    @Override
    public List<CoverageGap> detectGaps(LocalDate windowStart, Instant now) {
      return delegate.detectGaps(windowStart, now);
    }

    @Override
    public void resolveGap(
        LocalDate publicationDate, int modalityCode, BrazilianState state, Instant now) {
      delegate.resolveGap(publicationDate, modalityCode, state, now);
    }

    @Override
    public List<CoverageGap> openGaps() {
      return delegate.openGaps();
    }
  }
}
