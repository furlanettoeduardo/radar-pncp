package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpTimestamps;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One day of discovery: which Sao Paulo date a run belongs to, and which publication dates it owes.
 *
 * <p>Every date here is a Brazilian calendar date, computed from the clock in {@code
 * America/Sao_Paulo} rather than in the JVM's zone. The instance runs in us-east-1 with its clock
 * in UTC, so for three hours every evening UTC is already on tomorrow while PNCP is not, and a run
 * asking for "today" in the wrong zone would ask for a day that has not happened yet.
 *
 * <p>The clock is a parameter for the same reason the scoring engine takes an evaluation instant: a
 * component that reads the clock cannot be tested at the boundary that matters, and this one has a
 * boundary that matters every single evening. See ADR 0005.
 *
 * <p><b>Coverage is the subtle part.</b> A cycle fetches every date in its window, including today,
 * and fetching today is worth doing — the notices appear hours earlier than they otherwise would.
 * But today is still open, so more will be published after the fetch, and a fetch of an open date
 * can never be the last word on it. Only a cycle that began after a date closed settles it, which
 * is exactly {@code cycleDate > publicationDate}. A lookback of N therefore buys N covering cycles,
 * not N+1.
 */
public record DiscoveryCycle(LocalDate date, int lookbackDays) {

  public DiscoveryCycle {
    Objects.requireNonNull(date, "a cycle must know which day it is");
    if (lookbackDays < 1) {
      throw new IllegalArgumentException(
          ("a lookback of %d would leave every publication date with no covering cycle: only a "
                  + "cycle that runs after a date closes can settle it")
              .formatted(lookbackDays));
    }
  }

  public static DiscoveryCycle at(Clock clock, int lookbackDays) {
    Objects.requireNonNull(clock, "a cycle needs a clock");
    return new DiscoveryCycle(
        LocalDate.now(clock.withZone(PncpTimestamps.PNCP_ZONE)), lookbackDays);
  }

  /** The oldest publication date this cycle still owes. Anything earlier has left the window. */
  public LocalDate windowStart() {
    return date.minusDays(lookbackDays);
  }

  /**
   * Every publication date in the window, oldest first.
   *
   * <p>Oldest first is load bearing rather than tidy: the oldest date has the fewest covering
   * cycles left, so if a run is cut short by its budget the work that got done is the work closest
   * to being lost.
   */
  public List<LocalDate> windowDates() {
    return Stream.iterate(windowStart(), day -> !day.isAfter(date), day -> day.plusDays(1))
        .toList();
  }

  /**
   * Whether completing a fetch in this cycle would settle {@code publicationDate} for good.
   *
   * <p>False for today, which is not a reason to skip it: an open date is worth fetching and simply
   * cannot be finished.
   */
  public boolean coversWhenCompleted(LocalDate publicationDate) {
    return date.isAfter(publicationDate);
  }
}
