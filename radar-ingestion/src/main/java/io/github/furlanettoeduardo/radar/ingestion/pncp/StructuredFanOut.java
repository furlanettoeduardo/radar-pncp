package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Runs a batch of independent jobs on virtual threads, bounded, cancelling the rest when one fails.
 *
 * <p><b>This is the only class in the project that touches a preview API.</b> That is deliberate
 * containment: {@code StructuredTaskScope} is a preview in Java 21, its shape changes before it is
 * finalised in Java 25, and a JDK upgrade past 21 is a rewrite of this file rather than a
 * recompile. Everything else talks to the ordinary method below. See {@code docs/adr/0008}.
 *
 * <p>Two properties are worth stating because they are the whole reason for using it:
 *
 * <ul>
 *   <li><b>A failure cancels its siblings.</b> The first job to throw shuts the scope down, and
 *       there is no point continuing to hammer a public API for pages whose result is about to be
 *       discarded.
 *   <li><b>Nothing outlives the call.</b> The scope is closed by try-with-resources, and closing
 *       interrupts every unfinished subtask and then waits for all of them to terminate. The
 *       exception cannot propagate while threads are still running, which is exactly what a bare
 *       executor does not promise.
 * </ul>
 *
 * <p>The scope itself has no concurrency ceiling: forking a thousand subtasks forks a thousand
 * virtual threads, and they would all hit the network at once. The semaphore is the ceiling, taken
 * inside each subtask so that a waiting job holds nothing but a parked virtual thread.
 *
 * <p>One failure is also shared, not rediscovered. The first job to fail records why, and any job
 * that has not started work yet abandons instead of running. Shutting the scope down mostly
 * achieves this already, but only mostly: a failing job releases its permit before the scope has
 * processed the failure, and in that window a waiting job can acquire it and start calling an
 * upstream already known to be down. The latch closes that window, which turns a property that held
 * by luck into one that holds by construction.
 *
 * <p>Nor does it bound the whole operation. Per job timeouts bound one call; a slow upstream and
 * enough jobs keep an invocation alive for as long as the arithmetic allows. The deadline is the
 * bound, and it matters most once a scheduler is periodically triggering this: an unbounded job
 * under a periodic trigger is how runs begin overlapping.
 */
public final class StructuredFanOut {

  private final int maxConcurrent;
  private final Duration deadline;

  public StructuredFanOut(int maxConcurrent, Duration deadline) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException(
          "at least one job must be allowed to run but was " + maxConcurrent);
    }
    Objects.requireNonNull(deadline, "a fan out must have a whole operation deadline");
    if (deadline.isNegative() || deadline.isZero()) {
      throw new IllegalArgumentException(
          "the fan out deadline must be positive but was " + deadline);
    }
    this.maxConcurrent = maxConcurrent;
    this.deadline = deadline;
  }

  private static void abandonIfUpstreamIsDown(AtomicReference<RuntimeException> firstFailure) {
    RuntimeException alreadyFailed = firstFailure.get();
    if (alreadyFailed != null) {
      throw new FanOutAbandonedException(
          "a sibling job already failed with: " + alreadyFailed.getMessage());
    }
  }

  public <T, R> List<R> runAll(List<T> inputs, Function<T, R> job) {
    if (inputs.isEmpty()) {
      return List.of();
    }

    Semaphore permits = new Semaphore(maxConcurrent);
    AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      List<StructuredTaskScope.Subtask<R>> subtasks =
          inputs.stream()
              .map(
                  input ->
                      scope.fork(
                          () -> {
                            abandonIfUpstreamIsDown(firstFailure);
                            permits.acquire();
                            try {
                              // Checked again after the permit: it may have been freed by the very
                              // job that just failed.
                              abandonIfUpstreamIsDown(firstFailure);
                              return job.apply(input);
                            } catch (RuntimeException failure) {
                              // Recorded before the permit is released, so nothing can slip in
                              // between the two and start work against a dead upstream.
                              if (!(failure instanceof FanOutAbandonedException)) {
                                firstFailure.compareAndSet(null, failure);
                              }
                              throw failure;
                            } finally {
                              permits.release();
                            }
                          }))
              .toList();

      scope.joinUntil(Instant.now().plus(deadline));
      // Rethrow what actually failed rather than a wrapper: the caller distinguishes an
      // unavailable PNCP from a refused request, and a wrapper would hide that.
      scope.throwIfFailed(
          cause -> {
            // Always report what actually went wrong, never an abandonment caused by it.
            RuntimeException recorded = firstFailure.get();
            if (recorded != null) {
              return recorded;
            }
            return cause instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("a fan out job failed", cause);
          });

      return subtasks.stream().map(StructuredTaskScope.Subtask::get).toList();

    } catch (TimeoutException expired) {
      // Closing the scope on the way out interrupts every unfinished subtask and waits for it, so
      // this propagates with nothing still running, exactly as a job failure does.
      throw new FanOutTimedOutException(
          "the fan out of %d jobs did not finish within %s".formatted(inputs.size(), deadline));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while fanning out", interrupted);
    }
  }
}
