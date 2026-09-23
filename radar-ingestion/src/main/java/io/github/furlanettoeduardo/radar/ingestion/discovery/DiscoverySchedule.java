package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When discovery runs, and why more often than daily.
 *
 * <p><b>Freshness cadence and attempt cadence are different things.</b> PNCP's narrowest query is a
 * calendar date, so fetching more often than daily re-fetches the same pages and buys nothing; that
 * argument is in ADR 0010 and still holds. It says nothing about how often we should <em>try</em>,
 * and one attempt a day is fragile against an API measured failing 36 of 42 calls in an afternoon.
 *
 * <p>So this runs eight times a day and the chunk table decides what work is left. On a healthy day
 * seven of those eight find every chunk already complete and do nothing but read a handful of rows.
 *
 * <p><b>Why 00:17 and not 00:00.</b> The first attempt after midnight is the first one that can
 * cover yesterday, since a date is only settled by a cycle that began after it closed. Seventeen
 * minutes past keeps us off the hour, where every other cron job in the country arrives at PNCP at
 * the same moment.
 *
 * <p>The zone is explicit. The host runs in UTC and PNCP's dates are Brazilian, so a cron left on
 * the system zone would put the first run of the day at 21:17 the previous evening, before the day
 * it is meant to cover has ended.
 *
 * <p>ShedLock guards concurrency and nothing else. Whether a run is <em>due</em> is answered by the
 * chunk table, because a lock is taken when a run starts and knows nothing about whether it
 * succeeded. That is the same distinction that ruled out using {@code lockAtLeastFor} as a
 * once-a-day guard.
 */
@Component
public final class DiscoverySchedule {

  private static final Logger LOG = LoggerFactory.getLogger(DiscoverySchedule.class);

  static final String LOCK_NAME = "radar-discovery";

  /**
   * How much longer than a run's own deadline the lock is held.
   *
   * <p>Two, so the lock always outlives the work it protects. {@code lockAtMostFor} is the promise
   * that a crashed instance stops blocking the next one, and were it shorter than the run deadline
   * a slow run would still be working when a second instance was told the lock was free. Derived
   * rather than configured separately: two numbers that must stay in a relation are two chances to
   * break it.
   */
  private static final int LOCK_HEADROOM = 2;

  private final ProcurementDiscoveryJob job;
  private final LockingTaskExecutor locks;
  private final PncpProperties pncp;
  private final Clock clock;

  public DiscoverySchedule(
      ProcurementDiscoveryJob job, LockingTaskExecutor locks, PncpProperties pncp, Clock clock) {
    this.job = job;
    this.locks = locks;
    this.pncp = Objects.requireNonNull(pncp, "a schedule needs the run deadline it derives from");
    this.clock = clock;
  }

  /** Every three hours at seventeen minutes past, in Brasilia. */
  @Scheduled(cron = "0 17 */3 * * *", zone = "America/Sao_Paulo")
  public void runDiscovery() {
    locks.executeWithLock(
        (Runnable)
            () -> {
              DiscoveryReport report = job.discover();
              LOG.info(
                  "scheduled discovery finished: {} discovered, {} published",
                  report.discovered(),
                  report.published());
            },
        new LockConfiguration(clock.instant(), LOCK_NAME, lockAtMostFor(), Duration.ZERO));
  }

  /**
   * Always longer than a run can legitimately take, so a slow run is never joined by a second
   * instance. {@code lockAtLeastFor} is zero on purpose: the next attempt three hours later should
   * be free to pick up whatever this one did not finish.
   */
  Duration lockAtMostFor() {
    return pncp.operationDeadline().multipliedBy(LOCK_HEADROOM);
  }
}
