package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
   * The latch closes a narrow race, so this is built tightly enough to see it.
   *
   * <p>Shutting the scope down already stops jobs still waiting on a permit, because {@code
   * Semaphore.acquire} is interruptible. The remaining gap is that a failing job releases its
   * permit in a {@code finally} <em>before</em> the scope has processed the failure, so a waiting
   * job can take the freed permit in between.
   *
   * <p>Two things make that observable. The cap is one, so exactly one job runs at a time and a
   * single job slipping through is the difference between empty and not. And whichever job wins the
   * permit race is the one that fails, rather than a job chosen by index: forking is ordered,
   * acquiring is not, and an earlier version of this test broke one run in five purely because it
   * assumed otherwise.
   */
  @Test
  @DisplayName("no job starts work once a sibling has established the upstream is down")
  void noJobStartsWorkOnceTheUpstreamIsKnownDown() {
    int iterations = 10;

    for (int iteration = 1; iteration <= iterations; iteration++) {
      Set<Integer> enteredWork = ConcurrentHashMap.newKeySet();
      AtomicBoolean firstToRun = new AtomicBoolean(true);

      assertThatThrownBy(
              () ->
                  new StructuredFanOut(1, Duration.ofSeconds(30))
                      .runAll(
                          IntStream.rangeClosed(1, 20).boxed().toList(),
                          input -> {
                            if (firstToRun.compareAndSet(true, false)) {
                              sleep(Duration.ofMillis(50));
                              throw new PncpUnavailableException("PNCP is down");
                            }
                            enteredWork.add(input);
                            sleep(Duration.ofMillis(100));
                            return input;
                          }))
          .isInstanceOf(PncpUnavailableException.class);

      assertThat(enteredWork)
          .as(
              "iteration %d: jobs that worked against an upstream already known to be down",
              iteration)
          .isEmpty();
    }
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
