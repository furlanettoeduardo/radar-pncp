package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every {@link DiscoveryChunkRepository} must do, whatever it stores into.
 *
 * <p>The gap detection tests are the important ones. Everything else here is bookkeeping that would
 * be caught the first time anybody looked; a coverage gap that goes undetected is, by construction,
 * the failure nobody looks at, because the whole point of it is that the notices are gone and
 * nothing in the system knows.
 */
public abstract class DiscoveryChunkRepositoryContract {

  protected static final BrazilianState SP = BrazilianState.SP;
  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

  /** A fresh, empty repository. */
  protected abstract DiscoveryChunkRepository repository();

  private static LocalDate day(int dayOfMonth) {
    return LocalDate.of(2026, 9, dayOfMonth);
  }

  private static DiscoveryChunk chunk(int cycleDay, int publicationDay, int modality) {
    return DiscoveryChunk.scheduled(day(cycleDay), day(publicationDay), modality, SP);
  }

  private static void planAndComplete(DiscoveryChunkRepository repository, DiscoveryChunk chunk) {
    repository.planIfAbsent(chunk, NOW);
    repository.complete(chunk, NOW, 1, 1);
  }

  @Test
  @DisplayName("planning creates a chunk once; planning again changes nothing")
  void planningIsIdempotent() {
    DiscoveryChunkRepository repository = repository();
    DiscoveryChunk chunk = chunk(24, 23, 6);

    assertThat(repository.planIfAbsent(chunk, NOW)).isTrue();
    assertThat(repository.planIfAbsent(chunk, NOW)).isFalse();
    assertThat(repository.pending(day(24))).hasSize(1);
  }

  @Test
  @DisplayName("planning never turns a manual backfill back into a scheduled chunk")
  void planningNeverRewritesAManualChunk() {
    DiscoveryChunkRepository repository = repository();
    DiscoveryChunk manual = new DiscoveryChunk(day(24), day(23), 6, SP, ChunkOrigin.MANUAL, 0);
    repository.planIfAbsent(manual, NOW);

    repository.planIfAbsent(chunk(24, 23, 6), NOW);

    assertThat(repository.pending(day(24)))
        .singleElement()
        .extracting(DiscoveryChunk::origin)
        .isEqualTo(ChunkOrigin.MANUAL);
  }

  @Test
  @DisplayName("pending returns this cycle's incomplete chunks, oldest publication date first")
  void pendingIsOldestFirst() {
    DiscoveryChunkRepository repository = repository();
    repository.planIfAbsent(chunk(24, 24, 6), NOW);
    repository.planIfAbsent(chunk(24, 22, 6), NOW);
    repository.planIfAbsent(chunk(24, 23, 6), NOW);

    assertThat(repository.pending(day(24)))
        .as("the oldest date has the fewest covering cycles left, so it is worked first")
        .extracting(DiscoveryChunk::publicationDate)
        .containsExactly(day(22), day(23), day(24));
  }

  @Test
  @DisplayName("a completed chunk is no longer pending")
  void completedChunksAreNotPending() {
    DiscoveryChunkRepository repository = repository();
    DiscoveryChunk chunk = chunk(24, 23, 6);
    planAndComplete(repository, chunk);

    assertThat(repository.pending(day(24))).isEmpty();
  }

  @Test
  @DisplayName("a manual chunk from another cycle is still pending, however old it is")
  void aManualChunkOutsideTheCycleIsStillPending() {
    DiscoveryChunkRepository repository = repository();
    DiscoveryChunk manual = new DiscoveryChunk(day(10), day(9), 6, SP, ChunkOrigin.MANUAL, 0);
    repository.planIfAbsent(manual, NOW);

    assertThat(repository.pending(day(24)))
        .as("a backfill is a standing request, not a request for one particular day")
        .singleElement()
        .extracting(DiscoveryChunk::publicationDate)
        .isEqualTo(day(9));
  }

  @Test
  @DisplayName("completing is conditional: the second completion is told it lost")
  void completingTwiceIsRefused() {
    DiscoveryChunkRepository repository = repository();
    DiscoveryChunk chunk = chunk(24, 23, 6);
    repository.planIfAbsent(chunk, NOW);

    assertThat(repository.complete(chunk, NOW, 10, 40)).isTrue();
    assertThat(repository.complete(chunk, NOW, 10, 40))
        .as("otherwise a duplicate run double counts what it published")
        .isFalse();
  }

  @Test
  @DisplayName("attempts count only the attempts that actually happened")
  void attemptsAreCounted() {
    DiscoveryChunkRepository repository = repository();
    DiscoveryChunk chunk = chunk(24, 23, 6);
    repository.planIfAbsent(chunk, NOW);

    repository.recordAttempt(chunk, NOW);
    repository.recordAttempt(chunk, NOW);

    assertThat(repository.pending(day(24)))
        .singleElement()
        .extracting(DiscoveryChunk::attempts)
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a chunk completed only on its own day is NOT coverage: the date was still open")
  void aSameDayFetchDoesNotCover() {
    DiscoveryChunkRepository repository = repository();
    planAndComplete(repository, chunk(20, 20, 6));

    List<CoverageGap> gaps = repository.detectGaps(day(21), NOW);

    assertThat(gaps)
        .as("everything published on the 20th after that fetch was never collected")
        .extracting(CoverageGap::publicationDate)
        .containsExactly(day(20));
  }

  @Test
  @DisplayName("a chunk completed on a later cycle covers the date, and no gap is recorded")
  void aLaterCycleCovers() {
    DiscoveryChunkRepository repository = repository();
    planAndComplete(repository, chunk(20, 20, 6));
    planAndComplete(repository, chunk(21, 20, 6));

    assertThat(repository.detectGaps(day(21), NOW)).isEmpty();
  }

  @Test
  @DisplayName("a gap is reported exactly once, however often detection runs")
  void aGapIsReportedOnce() {
    DiscoveryChunkRepository repository = repository();
    planAndComplete(repository, chunk(20, 20, 6));

    assertThat(repository.detectGaps(day(21), NOW)).hasSize(1);
    assertThat(repository.detectGaps(day(21), NOW))
        .as("an alert that repeats every three hours is an alert nobody reads")
        .isEmpty();
    assertThat(repository.openGaps()).hasSize(1);
  }

  @Test
  @DisplayName("five days down: every date that left the window becomes exactly one gap")
  void anOutageLeavesOneGapPerLostDate() {
    DiscoveryChunkRepository repository = repository();
    // Cycle 15 ran and covered everything up to the 14th. Then nothing ran until the 21st, so the
    // 15th through the 17th were never planned at all: no rows, and nothing to notice them by.
    planAndComplete(repository, chunk(15, 14, 6));

    List<CoverageGap> gaps = repository.detectGaps(day(18), NOW);

    assertThat(gaps)
        .as(
            "dates that were never planned must be as visible as dates that were planned and"
                + " failed")
        .extracting(CoverageGap::publicationDate)
        .containsExactly(day(15), day(16), day(17));
  }

  @Test
  @DisplayName("a modality configured later has no history to be blamed for")
  void aNewModalityCreatesNoGapsBeforeItsFirstPlannedDate() {
    DiscoveryChunkRepository repository = repository();
    planAndComplete(repository, chunk(15, 14, 6));
    // Modality 8 turned up for the first time on the 20th.
    planAndComplete(repository, chunk(20, 19, 8));

    List<CoverageGap> gaps = repository.detectGaps(day(21), NOW);

    assertThat(gaps)
        .filteredOn(gap -> gap.modalityCode() == 8)
        .as("modality 8 is answerable only from the first date anybody asked it for")
        .extracting(CoverageGap::publicationDate)
        .allSatisfy(date -> assertThat(date).isAfterOrEqualTo(day(19)));
    assertThat(gaps)
        .filteredOn(gap -> gap.modalityCode() == 6)
        .as("modality 6 has been answerable since the 14th and its gaps go back that far")
        .extracting(CoverageGap::publicationDate)
        .contains(day(15));
  }

  @Test
  @DisplayName("a manual backfill does not make us retroactively responsible for a decade")
  void aManualChunkDoesNotExtendResponsibility() {
    DiscoveryChunkRepository repository = repository();
    planAndComplete(repository, chunk(20, 19, 6));
    DiscoveryChunk ancient =
        new DiscoveryChunk(day(20), LocalDate.of(2020, 1, 1), 6, SP, ChunkOrigin.MANUAL, 0);
    repository.planIfAbsent(ancient, NOW);
    repository.complete(ancient, NOW, 1, 1);

    assertThat(repository.detectGaps(day(20), NOW))
        .as("otherwise one backfill of an old date reports every date since as lost")
        .isEmpty();
  }

  @Test
  @DisplayName("a backfill that finally covers a lost date resolves its gap")
  void backfillingResolvesTheGap() {
    DiscoveryChunkRepository repository = repository();
    planAndComplete(repository, chunk(20, 20, 6));
    assertThat(repository.detectGaps(day(21), NOW)).hasSize(1);

    repository.resolveGap(day(20), 6, SP, NOW);

    assertThat(repository.openGaps()).isEmpty();
  }
}
