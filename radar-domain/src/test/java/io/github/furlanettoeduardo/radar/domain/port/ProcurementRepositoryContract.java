package io.github.furlanettoeduardo.radar.domain.port;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ProcurementRepository} must do, whatever it stores into.
 *
 * <p>Written as a contract rather than as two unrelated test classes because the fake and the real
 * adapter are only useful if they are interchangeable. A fake that is merely <em>similar</em> to
 * the database turns every domain test that uses it into a test of something that does not exist.
 *
 * <p>The conditional writes are the reason this exists. {@code ProcurementIngestion} retries on a
 * lost compare-and-set, and that retry loop is correct only if a losing writer genuinely returns
 * false instead of overwriting. An in-memory fake gets that right by picking the right map method;
 * a JDBC adapter gets it right only if the SQL is really conditional. Nothing but a shared contract
 * catches the day the two stop agreeing.
 */
public abstract class ProcurementRepositoryContract {

  private static final String ORIGINAL_HASH = "hash-as-first-seen";
  private static final String UPDATED_HASH = "hash-after-pncp-edited-it";

  /** A fresh, empty repository. One per test: every test here writes. */
  protected abstract ProcurementRepository repository();

  private static Procurement procurement(String controlNumber, String hash) {
    return aProcurement().identifiedBy(controlNumber).hashed(hash).build();
  }

  @Test
  @DisplayName("a control number nobody stored is absent, not an error and not a blank row")
  void anUnknownControlNumberIsAbsent() {
    PncpControlNumber unknown = new PncpControlNumber("00000000000000-1-000001/2026");

    assertThat(repository().findByControlNumber(unknown)).isEmpty();
  }

  @Test
  @DisplayName("an insert into an empty repository wins, and what went in comes back out")
  void anInsertWinsAndRoundTrips() {
    ProcurementRepository repository = repository();
    Procurement incoming = procurement("11111111000191-1-000001/2026", ORIGINAL_HASH);

    assertThat(repository.insertIfAbsent(incoming)).isTrue();
    assertThat(repository.findByControlNumber(incoming.controlNumber())).contains(incoming);
  }

  @Test
  @DisplayName("every field survives the round trip, including those allowed to be absent")
  void everyFieldSurvivesTheRoundTrip() {
    ProcurementRepository repository = repository();
    Procurement incoming =
        aProcurement()
            .identifiedBy("22222222000192-1-000002/2026")
            .describing("Aquisicao de servicos de informatica, com acentuacao removida")
            .in(BrazilianState.RJ)
            .withSecretBudget()
            .withoutSourceTimestamp()
            .openingAt(Instant.parse("2026-09-02T08:00:00.123456Z"))
            .closingAt(Instant.parse("2026-10-20T12:00:00.654321Z"))
            .hashed(ORIGINAL_HASH)
            .build();

    repository.insertIfAbsent(incoming);

    assertThat(repository.findByControlNumber(incoming.controlNumber()))
        .as("a secret budget, a missing source timestamp and sub-second precision all round trip")
        .contains(incoming);
  }

  @Test
  @DisplayName("a second insert of the same control number loses, and overwrites nothing")
  void aSecondInsertLosesAndChangesNothing() {
    ProcurementRepository repository = repository();
    Procurement first = procurement("33333333000193-1-000003/2026", ORIGINAL_HASH);
    Procurement second = procurement("33333333000193-1-000003/2026", UPDATED_HASH);

    assertThat(repository.insertIfAbsent(first)).isTrue();
    assertThat(repository.insertIfAbsent(second)).isFalse();
    assertThat(repository.findByControlNumber(first.controlNumber()))
        .as("the loser must not have written anything at all")
        .contains(first);
  }

  @Test
  @DisplayName("a replace wins when the stored hash is still the one that was read")
  void aReplaceWinsWhenNothingChanged() {
    ProcurementRepository repository = repository();
    Procurement stored = procurement("44444444000194-1-000004/2026", ORIGINAL_HASH);
    Procurement incoming = procurement("44444444000194-1-000004/2026", UPDATED_HASH);
    repository.insertIfAbsent(stored);

    assertThat(repository.replaceIfUnchanged(incoming, ORIGINAL_HASH)).isTrue();
    assertThat(repository.findByControlNumber(stored.controlNumber())).contains(incoming);
  }

  @Test
  @DisplayName("a replace loses when the row moved under it, and changes nothing")
  void aReplaceLosesWhenTheRowMovedUnderIt() {
    ProcurementRepository repository = repository();
    Procurement stored = procurement("55555555000195-1-000005/2026", UPDATED_HASH);
    Procurement incoming = procurement("55555555000195-1-000005/2026", "hash-from-a-stale-read");
    repository.insertIfAbsent(stored);

    assertThat(repository.replaceIfUnchanged(incoming, ORIGINAL_HASH))
        .as("the expected hash is no longer what is stored, so this writer lost the race")
        .isFalse();
    assertThat(repository.findByControlNumber(stored.controlNumber())).contains(stored);
  }

  @Test
  @DisplayName("a replace of a row that does not exist loses rather than creating one")
  void aReplaceOfNothingLoses() {
    ProcurementRepository repository = repository();
    Procurement incoming = procurement("66666666000196-1-000006/2026", UPDATED_HASH);

    assertThat(repository.replaceIfUnchanged(incoming, ORIGINAL_HASH)).isFalse();
    assertThat(repository.findByControlNumber(incoming.controlNumber()))
        .as("a conditional replace is not an upsert")
        .isEmpty();
  }

  @Test
  @DisplayName("exactly one of many concurrent inserts wins: the rest are told they lost")
  void exactlyOneConcurrentInsertWins() throws Exception {
    ProcurementRepository repository = repository();
    PncpControlNumber contended = new PncpControlNumber("77777777000197-1-000007/2026");

    int winners =
        countWinners(
            8,
            writer ->
                repository.insertIfAbsent(
                    aProcurement()
                        .identifiedBy(contended.value())
                        .hashed("hash-from-writer-" + writer)
                        .build()));

    assertThat(winners)
        .as("if two writers are both told they won, the ingestion retry loop is built on sand")
        .isEqualTo(1);
    assertThat(repository.findByControlNumber(contended)).isPresent();
  }

  @Test
  @DisplayName("exactly one of many concurrent replaces against the same read wins")
  void exactlyOneConcurrentReplaceWins() throws Exception {
    ProcurementRepository repository = repository();
    PncpControlNumber contended = new PncpControlNumber("88888888000198-1-000008/2026");
    repository.insertIfAbsent(procurement(contended.value(), ORIGINAL_HASH));

    int winners =
        countWinners(
            8,
            writer ->
                repository.replaceIfUnchanged(
                    aProcurement()
                        .identifiedBy(contended.value())
                        .hashed("hash-from-writer-" + writer)
                        .build(),
                    ORIGINAL_HASH));

    assertThat(winners)
        .as("every writer read the same hash, so exactly one of them may replace it")
        .isEqualTo(1);
  }

  /** Releases {@code writers} threads at once and counts how many were told they won. */
  private static int countWinners(int writers, IntPredicate write) throws Exception {
    CyclicBarrier allReady = new CyclicBarrier(writers);
    AtomicInteger winners = new AtomicInteger();

    try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
      List<Future<Boolean>> attempts =
          IntStream.range(0, writers)
              .mapToObj(
                  writer ->
                      pool.submit(
                          () -> {
                            allReady.await(10, TimeUnit.SECONDS);
                            boolean won = write.test(writer);
                            if (won) {
                              winners.incrementAndGet();
                            }
                            return won;
                          }))
              .toList();
      for (Future<Boolean> attempt : attempts) {
        attempt.get(20, TimeUnit.SECONDS);
      }
    }
    return winners.get();
  }
}
