package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Where chunk outcomes are kept.
 *
 * <p>Not a domain port, and the line is the same one {@code ProcurementPublisher} sits on: a port
 * belongs in the domain when a domain rule depends on it. Whether discovery is chunked by date, how
 * often it re-attempts and how long it stays responsible for a date are scheduling decisions about
 * a particular upstream, not business rules about procurement.
 *
 * <p>Two invariants live in the implementations rather than in a caller, because only the database
 * can enforce them without a race:
 *
 * <ul>
 *   <li>{@link #planIfAbsent} never overwrites an existing row, which is what stops a scheduled
 *       plan from quietly turning a human's backfill back into a scheduled chunk;
 *   <li>{@link #complete} is conditional on the chunk not already being complete, so a duplicate
 *       run cannot double count what it published.
 * </ul>
 */
public interface DiscoveryChunkRepository {

  /**
   * @return true if this call created the row, false if it already existed in any state.
   */
  boolean planIfAbsent(DiscoveryChunk chunk, Instant now);

  /**
   * Chunks still owed: everything incomplete from this cycle, plus every incomplete manual backfill
   * whatever cycle asked for it, oldest publication date first.
   */
  List<DiscoveryChunk> pending(LocalDate cycleDate);

  void recordAttempt(DiscoveryChunk chunk, Instant now);

  /**
   * @return true if this call completed the chunk, false if it was already complete.
   */
  boolean complete(DiscoveryChunk chunk, Instant now, int pagesFetched, int noticesPublished);

  void recordFailure(DiscoveryChunk chunk, Instant now, String reason);

  /**
   * Finds publication dates that have left the window without ever being covered, records each one
   * exactly once, and returns only those newly found.
   *
   * <p>Coverage is checked against the <em>calendar</em>, not against the rows that happen to
   * exist. A service that was down for a week never planned those dates at all, so a check that
   * only looked at existing rows would report nothing and lose the days in silence — which is the
   * failure the lookback is there to survive.
   */
  List<CoverageGap> detectGaps(LocalDate windowStart, Instant now);

  void resolveGap(LocalDate publicationDate, int modalityCode, BrazilianState state, Instant now);

  /** Gaps nobody has backfilled yet. The runbook's health question. */
  List<CoverageGap> openGaps();
}
