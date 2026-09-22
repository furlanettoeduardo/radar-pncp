package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
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
 */
public final class StructuredFanOut {

  private final int maxConcurrent;

  public StructuredFanOut(int maxConcurrent) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException(
          "at least one job must be allowed to run but was " + maxConcurrent);
    }
    this.maxConcurrent = maxConcurrent;
  }

  public <T, R> List<R> runAll(List<T> inputs, Function<T, R> job) {
    if (inputs.isEmpty()) {
      return List.of();
    }

    Semaphore permits = new Semaphore(maxConcurrent);
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      List<StructuredTaskScope.Subtask<R>> subtasks =
          inputs.stream()
              .map(
                  input ->
                      scope.fork(
                          () -> {
                            permits.acquire();
                            try {
                              return job.apply(input);
                            } finally {
                              permits.release();
                            }
                          }))
              .toList();

      scope.join();
      // Rethrow what actually failed rather than a wrapper: the caller distinguishes an
      // unavailable PNCP from a refused request, and a wrapper would hide that.
      scope.throwIfFailed(
          cause ->
              cause instanceof RuntimeException runtime
                  ? runtime
                  : new IllegalStateException("a fan out job failed", cause));

      return subtasks.stream().map(StructuredTaskScope.Subtask::get).toList();

    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while fanning out", interrupted);
    }
  }
}
