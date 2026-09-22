package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
   * Overlap is forced rather than hoped for. Each job counts down a latch sized to the cap and then
   * waits on it, so the batch can only make progress once exactly {@code cap} jobs are in flight
   * together. That makes the assertion deterministic and stronger than "did not exceed": a cap that
   * was too low would deadlock the latch and time out, and one that was too high would push the
   * high water mark above it.
   *
   * <p>An earlier version slept instead and asserted overlap above one. That measured whatever the
   * machine happened to schedule, which is not a property of the code under test.
   */
  @Test
  @DisplayName("runs exactly as many jobs at once as the cap allows, no more and no fewer")
  void honoursTheConcurrencyCapExactly() {
    int cap = 3;
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger highWaterMark = new AtomicInteger();
    CountDownLatch capReached = new CountDownLatch(cap);
    AtomicInteger latchTimeouts = new AtomicInteger();

    fanOut(cap)
        .runAll(
            IntStream.rangeClosed(1, 30).boxed().toList(),
            input -> {
              int now = inFlight.incrementAndGet();
              highWaterMark.accumulateAndGet(now, Math::max);
              capReached.countDown();
              try {
                if (!capReached.await(5, TimeUnit.SECONDS)) {
                  latchTimeouts.incrementAndGet();
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
              }
              inFlight.decrementAndGet();
              return input;
            });

    assertThat(latchTimeouts.get())
        .as("a cap below %d would never let %d jobs run together", cap, cap)
        .isZero();
    assertThat(highWaterMark.get()).isEqualTo(cap);
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

  /**
   * An earlier version of this test asserted that at most {@code cap} jobs ever entered work, on
   * the assumption that the failing job would be among the first to take a permit. Forking is
   * ordered; <em>acquiring</em> is not. When the failing job lost the race for a permit, later jobs
   * ran a full 300ms each before it ever failed, and the assertion broke about one run in five.
   *
   * <p>What the latch actually promises is ordering-independent: once a failure is recorded, jobs
   * that have not started are abandoned rather than run. So the assertion is that most of the batch
   * never ran, which is true however the permits were handed out.
   */
  @Test
  @DisplayName("a failure abandons the rest of the batch instead of working through it")
  void aFailureAbandonsTheRestOfTheBatch() {
    int jobs = 40;
    Set<Integer> enteredWork = ConcurrentHashMap.newKeySet();

    assertThatThrownBy(
            () ->
                new StructuredFanOut(2, Duration.ofSeconds(30))
                    .runAll(
                        IntStream.rangeClosed(1, jobs).boxed().toList(),
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

    assertThat(enteredWork)
        .as("jobs that did work against an upstream already known to be down")
        .hasSizeLessThan(jobs / 2);
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
