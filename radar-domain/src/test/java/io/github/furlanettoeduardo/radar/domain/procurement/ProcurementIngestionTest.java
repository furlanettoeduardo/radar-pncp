package io.github.furlanettoeduardo.radar.domain.procurement;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision table for recording a discovered procurement, and what happens when two consumers
 * decide at the same time.
 *
 * <p>Two rules, and every row below is one of them or their interaction:
 *
 * <ul>
 *   <li><b>Idempotency.</b> Identical content is not stored twice.
 *   <li><b>Last write wins by source, not by arrival.</b> SQS is unordered, so the only usable
 *       notion of "newer" is the one PNCP publishes. An older source timestamp is rejected whatever
 *       order the messages happened to arrive in.
 * </ul>
 *
 * <p>The rule lives here and only here. The repository offers two conditional writes and knows
 * nothing about timestamps or who should win: it only promises "write if nothing has changed since
 * I read". That is a mechanism, not a policy, so there is no second copy of the rule to drift.
 */
class ProcurementIngestionTest {

  private static final Instant EARLIER = Instant.parse("2026-09-01T17:22:01Z");
  private static final Instant LATER = Instant.parse("2026-09-05T09:00:00Z");

  private final InMemoryProcurements stored = new InMemoryProcurements();
  private final ProcurementIngestion ingestion = new ProcurementIngestion(stored);

  // ---------------------------------------------------------------- the decision table

  @Test
  @DisplayName("a procurement nobody has seen before is stored")
  void anUnknownProcurementIsStored() {
    Procurement incoming = aProcurement().hashed("aaa").updatedAt(EARLIER).build();

    assertThat(ingestion.record(incoming)).isInstanceOf(IngestionOutcome.Stored.class);
    assertThat(stored.findByControlNumber(incoming.controlNumber())).contains(incoming);
  }

  @Test
  @DisplayName("the same content hash is not stored again")
  void sameContentHashIsNotStoredAgain() {
    ingestion.record(aProcurement().hashed("aaa").updatedAt(EARLIER).build());

    IngestionOutcome outcome =
        ingestion.record(aProcurement().hashed("aaa").updatedAt(LATER).build());

    assertThat(outcome).isInstanceOf(IngestionOutcome.Unchanged.class);
    assertThat(stored.writes()).isEqualTo(1);
  }

  @Test
  @DisplayName("different content with a newer source timestamp replaces what is stored")
  void newerSourceTimestampReplacesWhatIsStored() {
    ingestion.record(aProcurement().hashed("aaa").updatedAt(EARLIER).build());
    Procurement newer = aProcurement().hashed("bbb").updatedAt(LATER).build();

    assertThat(ingestion.record(newer)).isInstanceOf(IngestionOutcome.Stored.class);
    assertThat(stored.findByControlNumber(newer.controlNumber())).contains(newer);
  }

  @Test
  @DisplayName("different content with an older source timestamp is rejected as stale")
  void olderSourceTimestampIsRejectedAsStale() {
    Procurement current = aProcurement().hashed("bbb").updatedAt(LATER).build();
    ingestion.record(current);

    IngestionOutcome outcome =
        ingestion.record(aProcurement().hashed("aaa").updatedAt(EARLIER).build());

    assertThat(outcome).isInstanceOf(IngestionOutcome.Stale.class);
    assertThat(stored.findByControlNumber(current.controlNumber())).contains(current);
  }

  @Test
  @DisplayName(
      "different content with equal source timestamps is stored: the content is the tiebreak")
  void equalSourceTimestampsWithDifferentContentAreStored() {
    ingestion.record(aProcurement().hashed("aaa").updatedAt(EARLIER).build());
    Procurement sameInstant = aProcurement().hashed("bbb").updatedAt(EARLIER).build();

    assertThat(ingestion.record(sameInstant)).isInstanceOf(IngestionOutcome.Stored.class);
    assertThat(stored.findByControlNumber(sameInstant.controlNumber())).contains(sameInstant);
  }

  @Test
  @DisplayName(
      "an absent incoming timestamp falls back to the content hash, so updates are not stranded")
  void absentIncomingTimestampFallsBackToTheContentHash() {
    ingestion.record(aProcurement().hashed("aaa").updatedAt(LATER).build());
    Procurement undated = aProcurement().hashed("bbb").withoutSourceTimestamp().build();

    assertThat(ingestion.record(undated)).isInstanceOf(IngestionOutcome.Stored.class);
  }

  @Test
  @DisplayName("an absent stored timestamp falls back to the content hash")
  void absentStoredTimestampFallsBackToTheContentHash() {
    ingestion.record(aProcurement().hashed("aaa").withoutSourceTimestamp().build());
    Procurement dated = aProcurement().hashed("bbb").updatedAt(EARLIER).build();

    assertThat(ingestion.record(dated)).isInstanceOf(IngestionOutcome.Stored.class);
  }

  @Test
  @DisplayName("both timestamps absent falls back to the content hash")
  void bothTimestampsAbsentFallsBackToTheContentHash() {
    ingestion.record(aProcurement().hashed("aaa").withoutSourceTimestamp().build());
    Procurement changed = aProcurement().hashed("bbb").withoutSourceTimestamp().build();

    assertThat(ingestion.record(changed)).isInstanceOf(IngestionOutcome.Stored.class);
    assertThat(stored.writes()).isEqualTo(2);
  }

  @Test
  @DisplayName("an identical hash wins over timestamps entirely, even an absent one")
  void identicalHashWinsOverTimestamps() {
    ingestion.record(aProcurement().hashed("aaa").withoutSourceTimestamp().build());

    IngestionOutcome outcome =
        ingestion.record(aProcurement().hashed("aaa").updatedAt(LATER).build());

    assertThat(outcome).isInstanceOf(IngestionOutcome.Unchanged.class);
    assertThat(stored.writes()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- losing the race

  @Test
  @DisplayName("losing an insert race re-reads and decides again rather than failing")
  void aLostInsertRaceReDecidesRatherThanFailing() {
    Procurement incoming = aProcurement().hashed("aaa").updatedAt(EARLIER).build();
    // Somebody else inserts the identical content between our read and our write.
    stored.beforeNextWrite(() -> stored.insertIfAbsent(incoming));

    IngestionOutcome outcome = ingestion.record(incoming);

    assertThat(outcome).isInstanceOf(IngestionOutcome.Unchanged.class);
    assertThat(stored.writes()).isEqualTo(1);
  }

  @Test
  @DisplayName("losing a replace race re-reads and decides again against what actually won")
  void aLostReplaceRaceReDecidesAgainstTheWinner() {
    ingestion.record(aProcurement().hashed("aaa").updatedAt(EARLIER).build());
    Procurement mine = aProcurement().hashed("bbb").updatedAt(LATER).build();
    // Somebody else stores an even newer version first; ours is then stale.
    Procurement theirs = aProcurement().hashed("ccc").updatedAt(LATER.plusSeconds(60)).build();
    stored.beforeNextWrite(() -> stored.replaceIfUnchanged(theirs, "aaa"));

    IngestionOutcome outcome = ingestion.record(mine);

    assertThat(outcome).isInstanceOf(IngestionOutcome.Stale.class);
    assertThat(stored.findByControlNumber(mine.controlNumber())).contains(theirs);
  }

  @Test
  @DisplayName("giving up after three attempts lets SQS redeliver rather than looping forever")
  void givesUpAfterThreeAttempts() {
    Procurement incoming = aProcurement().hashed("aaa").updatedAt(EARLIER).build();
    stored.rejectEveryConditionalWrite();

    assertThatThrownBy(() -> ingestion.record(incoming))
        .isInstanceOf(ProcurementContentionException.class)
        .hasMessageContaining("3");
  }

  @Test
  @DisplayName("two consumers racing conflicting versions leave the newer one stored")
  void racingConsumersLeaveTheNewerVersionStored() throws Exception {
    Procurement older = aProcurement().hashed("older").updatedAt(EARLIER).build();
    Procurement newer = aProcurement().hashed("newer").updatedAt(LATER).build();
    ingestion.record(aProcurement().hashed("base").updatedAt(EARLIER.minusSeconds(60)).build());

    CyclicBarrier bothReady = new CyclicBarrier(2);
    List<Thread> racers = List.of(recordOn(bothReady, older), recordOn(bothReady, newer));
    racers.forEach(Thread::start);
    for (Thread racer : racers) {
      racer.join();
    }

    assertThat(stored.findByControlNumber(newer.controlNumber()))
        .as("whichever order they ran in, the newer source timestamp must survive")
        .contains(newer);
  }

  private Thread recordOn(CyclicBarrier barrier, Procurement procurement) {
    return new Thread(
        () -> {
          try {
            barrier.await();
            ingestion.record(procurement);
          } catch (ProcurementContentionException contended) {
            // Acceptable: in production SQS would redeliver. The surviving row is what matters.
          } catch (Exception unexpected) {
            throw new IllegalStateException(unexpected);
          }
        });
  }

  /**
   * A fake, not a mock, and thread safe on purpose: the conditional writes have to behave like real
   * compare-and-set or the race tests above would prove nothing.
   */
  private static final class InMemoryProcurements implements ProcurementRepository {

    private final ConcurrentHashMap<PncpControlNumber, Procurement> rows =
        new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicBoolean rejectEverything = new AtomicBoolean();
    private volatile Runnable beforeNextWrite;

    int writes() {
      return writes.get();
    }

    void rejectEveryConditionalWrite() {
      rejectEverything.set(true);
    }

    /** Simulates another writer slipping in between our read and our conditional write. */
    void beforeNextWrite(Runnable interference) {
      this.beforeNextWrite = interference;
    }

    private void runInterference() {
      Runnable once = beforeNextWrite;
      if (once != null) {
        beforeNextWrite = null;
        once.run();
      }
    }

    @Override
    public Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber) {
      return Optional.ofNullable(rows.get(controlNumber));
    }

    @Override
    public boolean insertIfAbsent(Procurement procurement) {
      runInterference();
      if (rejectEverything.get()) {
        return false;
      }
      boolean inserted = rows.putIfAbsent(procurement.controlNumber(), procurement) == null;
      if (inserted) {
        writes.incrementAndGet();
      }
      return inserted;
    }

    @Override
    public boolean replaceIfUnchanged(Procurement procurement, String expectedSourcePayloadHash) {
      runInterference();
      if (rejectEverything.get()) {
        return false;
      }
      AtomicBoolean replaced = new AtomicBoolean();
      rows.computeIfPresent(
          procurement.controlNumber(),
          (key, current) -> {
            if (current.sourcePayloadHash().equals(expectedSourcePayloadHash)) {
              replaced.set(true);
              return procurement;
            }
            return current;
          });
      if (replaced.get()) {
        writes.incrementAndGet();
      }
      return replaced.get();
    }
  }
}
