package io.github.furlanettoeduardo.radar.domain.procurement;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision table for recording a discovered procurement.
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
 * <p>Neither rule needs a queue or a database to be true, which is why they live here.
 */
class ProcurementIngestionTest {

  private static final Instant EARLIER = Instant.parse("2026-09-01T17:22:01Z");
  private static final Instant LATER = Instant.parse("2026-09-05T09:00:00Z");

  private final InMemoryProcurements stored = new InMemoryProcurements();
  private final ProcurementIngestion ingestion = new ProcurementIngestion(stored);

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
    assertThat(stored.writes).isEqualTo(1);
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
    assertThat(stored.writes).isEqualTo(2);
  }

  @Test
  @DisplayName("an identical hash wins over timestamps entirely, even an absent one")
  void identicalHashWinsOverTimestamps() {
    ingestion.record(aProcurement().hashed("aaa").withoutSourceTimestamp().build());

    IngestionOutcome outcome =
        ingestion.record(aProcurement().hashed("aaa").updatedAt(LATER).build());

    assertThat(outcome).isInstanceOf(IngestionOutcome.Unchanged.class);
    assertThat(stored.writes).isEqualTo(1);
  }

  /** A fake, not a mock: the assertions are about what was stored, not about calls. */
  private static final class InMemoryProcurements implements ProcurementRepository {

    private final Map<PncpControlNumber, Procurement> rows = new HashMap<>();
    private int writes;

    @Override
    public Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber) {
      return Optional.ofNullable(rows.get(controlNumber));
    }

    @Override
    public void save(Procurement procurement) {
      rows.put(procurement.controlNumber(), procurement);
      writes++;
    }
  }
}
