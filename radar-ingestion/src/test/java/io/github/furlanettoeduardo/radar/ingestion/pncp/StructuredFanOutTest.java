package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one class that hides the preview API. These tests assert the two properties that justify
 * using it at all: a real concurrency ceiling, and no subtask outliving a failure.
 */
class StructuredFanOutTest {

  @Test
  @DisplayName("returns one result per input, in input order")
  void returnsResultsInInputOrder() {
    List<Integer> inputs = IntStream.rangeClosed(1, 20).boxed().toList();

    List<String> results = fanOut(4).runAll(inputs, input -> "n" + input);

    assertThat(results).hasSize(20).startsWith("n1", "n2", "n3").endsWith("n20");
  }

  @Test
  @DisplayName("an empty fan out does no work rather than opening a scope for nothing")
  void anEmptyFanOutDoesNothing() {
    assertThat(fanOut(4).runAll(List.of(), input -> input)).isEmpty();
  }

  /**
   * Overlap is made real by holding every subtask long enough that they must contend. Counting
   * total calls would prove nothing about the ceiling; only the observed maximum in flight does.
   */
  @Test
  @DisplayName("never exceeds the concurrency cap, measured by maximum observed overlap")
  void neverExceedsTheConcurrencyCap() {
    int cap = 3;
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger highWaterMark = new AtomicInteger();

    fanOut(cap)
        .runAll(
            IntStream.rangeClosed(1, 30).boxed().toList(),
            input -> {
              int now = inFlight.incrementAndGet();
              highWaterMark.accumulateAndGet(now, Math::max);
              sleep(Duration.ofMillis(40));
              inFlight.decrementAndGet();
              return input;
            });

    assertThat(highWaterMark.get()).isLessThanOrEqualTo(cap);
    // And the work really did overlap, or the assertion above would be vacuous.
    assertThat(highWaterMark.get()).isGreaterThan(1);
  }

  @Test
  @DisplayName("every started subtask has terminated by the time the failure propagates")
  void everyStartedSubtaskTerminatesBeforeTheFailurePropagates() {
    Set<Integer> started = ConcurrentHashMap.newKeySet();
    Set<Integer> terminated = ConcurrentHashMap.newKeySet();
    Set<Integer> completedNormally = ConcurrentHashMap.newKeySet();

    assertThatThrownBy(
            () ->
                fanOut(8)
                    .runAll(
                        IntStream.rangeClosed(1, 40).boxed().toList(),
                        input -> {
                          started.add(input);
                          try {
                            if (input == 1) {
                              sleep(Duration.ofMillis(20));
                              throw new IllegalStateException("page " + input + " failed");
                            }
                            sleep(Duration.ofSeconds(5));
                            completedNormally.add(input);
                            return input;
                          } finally {
                            terminated.add(input);
                          }
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("page 1 failed");

    // The point: not "no exception leaked" but "nothing is still running".
    assertThat(terminated).containsExactlyInAnyOrderElementsOf(started);
    // And cancellation genuinely happened: the five second sleepers did not all finish.
    assertThat(completedNormally).hasSizeLessThan(started.size());
  }

  @Test
  @DisplayName("the original failure propagates, not a wrapper that hides it")
  void propagatesTheOriginalFailure() {
    assertThatThrownBy(
            () ->
                fanOut(2)
                    .runAll(
                        List.of(1, 2, 3),
                        input -> {
                          throw new PncpUnavailableException("PNCP answered 503");
                        }))
        .isInstanceOf(PncpUnavailableException.class)
        .hasMessage("PNCP answered 503");
  }

  @Test
  @DisplayName("stops at the whole operation deadline rather than running until every job is done")
  void stopsAtTheWholeOperationDeadline() {
    Set<Integer> started = ConcurrentHashMap.newKeySet();
    Set<Integer> terminated = ConcurrentHashMap.newKeySet();

    assertThatThrownBy(
            () ->
                new StructuredFanOut(8, Duration.ofMillis(200))
                    .runAll(
                        IntStream.rangeClosed(1, 40).boxed().toList(),
                        input -> {
                          started.add(input);
                          try {
                            sleep(Duration.ofSeconds(5));
                            return input;
                          } finally {
                            terminated.add(input);
                          }
                        }))
        .isInstanceOf(FanOutTimedOutException.class)
        .hasMessageContaining("did not finish within")
        .hasMessageContaining("40 jobs");

    // Same shape as the cancellation proof: nothing is still running when the failure surfaces.
    assertThat(terminated).containsExactlyInAnyOrderElementsOf(started);
    assertThat(started).isNotEmpty();
  }

  @Test
  @DisplayName("once one job has failed, no further job starts work")
  void noFurtherJobStartsWorkAfterTheFirstFailure() {
    int cap = 2;
    Set<Integer> enteredWork = ConcurrentHashMap.newKeySet();

    assertThatThrownBy(
            () ->
                new StructuredFanOut(cap, Duration.ofSeconds(30))
                    .runAll(
                        IntStream.rangeClosed(1, 40).boxed().toList(),
                        input -> {
                          if (input == 1) {
                            sleep(Duration.ofMillis(50));
                            throw new PncpUnavailableException("PNCP is down");
                          }
                          enteredWork.add(input);
                          // A whole retry budget, the thing we do not want repeated 39 times.
                          sleep(Duration.ofMillis(300));
                          return input;
                        }))
        .isInstanceOf(PncpUnavailableException.class);

    // Only jobs already holding a permit when the failure landed may have started.
    assertThat(enteredWork)
        .as("jobs that started work after the upstream was known to be down")
        .hasSizeLessThanOrEqualTo(cap);
  }

  private static StructuredFanOut fanOut(int maxConcurrent) {
    return new StructuredFanOut(maxConcurrent, Duration.ofSeconds(30));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", interrupted);
    }
  }
}
