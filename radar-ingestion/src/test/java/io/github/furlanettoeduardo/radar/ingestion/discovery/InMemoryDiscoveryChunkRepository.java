package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fake chunk store, held to {@link DiscoveryChunkRepositoryContract} beside the JDBC adapter.
 *
 * <p>Deliberately implements the calendar walk in Java rather than approximating it, because the
 * gap detection is the part most likely to differ between a map and a database, and a fake that
 * quietly agrees with the tests but not with PostgreSQL would make every job test meaningless.
 */
final class InMemoryDiscoveryChunkRepository implements DiscoveryChunkRepository {

  private record Key(
      LocalDate cycleDate, LocalDate publicationDate, int modalityCode, BrazilianState state) {
    static Key of(DiscoveryChunk chunk) {
      return new Key(
          chunk.cycleDate(), chunk.publicationDate(), chunk.modalityCode(), chunk.state());
    }
  }

  private record GapKey(LocalDate publicationDate, int modalityCode, BrazilianState state) {}

  private static final class Row {
    private final ChunkOrigin origin;
    private int attempts;
    private Instant completedAt;

    Row(ChunkOrigin origin) {
      this.origin = origin;
    }
  }

  private final Map<Key, Row> rows = new LinkedHashMap<>();
  private final Map<GapKey, Instant> gaps = new LinkedHashMap<>();
  private final Map<GapKey, Instant> resolved = new LinkedHashMap<>();

  @Override
  public boolean planIfAbsent(DiscoveryChunk chunk, Instant now) {
    return rows.putIfAbsent(Key.of(chunk), new Row(chunk.origin())) == null;
  }

  @Override
  public List<DiscoveryChunk> pending(LocalDate cycleDate) {
    return rows.entrySet().stream()
        .filter(entry -> entry.getValue().completedAt == null)
        .filter(
            entry ->
                entry.getKey().cycleDate().equals(cycleDate)
                    || entry.getValue().origin == ChunkOrigin.MANUAL)
        .map(
            entry ->
                new DiscoveryChunk(
                    entry.getKey().cycleDate(),
                    entry.getKey().publicationDate(),
                    entry.getKey().modalityCode(),
                    entry.getKey().state(),
                    entry.getValue().origin,
                    entry.getValue().attempts))
        .sorted(
            Comparator.comparing(DiscoveryChunk::publicationDate)
                .thenComparing(DiscoveryChunk::modalityCode))
        .toList();
  }

  @Override
  public void recordAttempt(DiscoveryChunk chunk, Instant now) {
    Row row = rows.get(Key.of(chunk));
    if (row != null) {
      row.attempts++;
    }
  }

  @Override
  public boolean complete(
      DiscoveryChunk chunk, Instant now, int pagesFetched, int noticesPublished) {
    Row row = rows.get(Key.of(chunk));
    if (row == null || row.completedAt != null) {
      return false;
    }
    row.completedAt = now;
    return true;
  }

  @Override
  public void recordFailure(DiscoveryChunk chunk, Instant now, String reason) {
    // Nothing here reads it back; the contract asserts on attempts and completion.
  }

  @Override
  public List<CoverageGap> detectGaps(LocalDate windowStart, Instant now) {
    List<CoverageGap> found = new ArrayList<>();
    responsibilities()
        .forEach(
            (pair, since) -> {
              for (LocalDate date = since; date.isBefore(windowStart); date = date.plusDays(1)) {
                if (isCovered(date, pair.modalityCode(), pair.state())) {
                  continue;
                }
                GapKey gap = new GapKey(date, pair.modalityCode(), pair.state());
                if (gaps.putIfAbsent(gap, now) == null) {
                  found.add(new CoverageGap(date, pair.modalityCode(), pair.state()));
                }
              }
            });
    return List.copyOf(found);
  }

  private record Pair(int modalityCode, BrazilianState state) {}

  /**
   * The first publication date each modality and state was ever scheduled for. Scheduled only: a
   * manual backfill of an old date must not make us answerable for everything since.
   */
  private Map<Pair, LocalDate> responsibilities() {
    Map<Pair, LocalDate> since = new LinkedHashMap<>();
    rows.forEach(
        (key, row) -> {
          if (row.origin != ChunkOrigin.SCHEDULED) {
            return;
          }
          Pair pair = new Pair(key.modalityCode(), key.state());
          LocalDate earliest = since.get(pair);
          if (earliest == null || key.publicationDate().isBefore(earliest)) {
            since.put(pair, key.publicationDate());
          }
        });
    return since;
  }

  /** Covered means completed by a cycle that began after the publication date closed. */
  private boolean isCovered(LocalDate publicationDate, int modalityCode, BrazilianState state) {
    return rows.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey().publicationDate().equals(publicationDate)
                    && entry.getKey().modalityCode() == modalityCode
                    && entry.getKey().state() == state
                    && entry.getValue().completedAt != null
                    && entry.getKey().cycleDate().isAfter(publicationDate));
  }

  @Override
  public void resolveGap(
      LocalDate publicationDate, int modalityCode, BrazilianState state, Instant now) {
    GapKey key = new GapKey(publicationDate, modalityCode, state);
    if (gaps.containsKey(key)) {
      resolved.putIfAbsent(key, now);
    }
  }

  @Override
  public List<CoverageGap> openGaps() {
    return gaps.keySet().stream()
        .filter(gap -> !resolved.containsKey(gap))
        .map(gap -> new CoverageGap(gap.publicationDate(), gap.modalityCode(), gap.state()))
        .toList();
  }

  /** Test helper: what a chunk recorded when it completed, if it did. */
  Optional<Instant> completedAt(DiscoveryChunk chunk) {
    return Optional.ofNullable(rows.get(Key.of(chunk))).map(row -> row.completedAt);
  }
}
