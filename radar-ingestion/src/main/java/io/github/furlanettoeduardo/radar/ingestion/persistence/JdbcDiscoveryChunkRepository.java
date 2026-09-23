package io.github.furlanettoeduardo.radar.ingestion.persistence;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.ingestion.discovery.ChunkOrigin;
import io.github.furlanettoeduardo.radar.ingestion.discovery.CoverageGap;
import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunk;
import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunkRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The PostgreSQL side of {@link DiscoveryChunkRepository}.
 *
 * <p>Registered explicitly by {@link PersistenceConfiguration} for the same reason as the
 * procurement adapter: {@code @Repository} asks for a CGLIB proxy that cannot subclass a final
 * class, and the exception translation it offers is already done by {@link JdbcClient}.
 */
public final class JdbcDiscoveryChunkRepository implements DiscoveryChunkRepository {

  private final JdbcClient jdbc;

  public JdbcDiscoveryChunkRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "a chunk repository needs a JDBC client");
  }

  @Override
  public boolean planIfAbsent(DiscoveryChunk chunk, Instant now) {
    // DO NOTHING rather than DO UPDATE is the whole guarantee that a scheduled plan cannot turn a
    // human's backfill back into a scheduled chunk. It is the absence of an UPDATE, not a rule
    // layered on top of one.
    return key(
                jdbc.sql(
                    """
                    INSERT INTO discovery_chunk (
                      cycle_date, publication_date, modality_code, state, origin, planned_at,
                      attempts)
                    VALUES (:cycleDate, :publicationDate, :modalityCode, :state, :origin, :now, 0)
                    ON CONFLICT (cycle_date, publication_date, modality_code, state) DO NOTHING
                    """),
                chunk)
            .param("origin", chunk.origin().name())
            .param("now", utc(now))
            .update()
        == 1;
  }

  @Override
  public List<DiscoveryChunk> pending(LocalDate cycleDate) {
    // A manual backfill is a standing request rather than a request for one particular day, so it
    // stays pending across cycles until it succeeds or somebody removes it.
    return jdbc.sql(
            """
            SELECT cycle_date, publication_date, modality_code, state, origin, attempts
            FROM discovery_chunk
            WHERE completed_at IS NULL
              AND (cycle_date = :cycleDate OR origin = 'MANUAL')
            ORDER BY publication_date, modality_code
            """)
        .param("cycleDate", cycleDate)
        .query(JdbcDiscoveryChunkRepository::toChunk)
        .list();
  }

  @Override
  public void recordAttempt(DiscoveryChunk chunk, Instant now) {
    key(
            jdbc.sql(
                """
                UPDATE discovery_chunk SET attempts = attempts + 1
                WHERE cycle_date = :cycleDate AND publication_date = :publicationDate
                  AND modality_code = :modalityCode AND state = :state
                """),
            chunk)
        .update();
  }

  @Override
  public boolean complete(
      DiscoveryChunk chunk, Instant now, int pagesFetched, int noticesPublished) {
    // Conditional on still being incomplete, so a duplicate run cannot double count what it
    // published. This statement runs only after every message of the chunk has been sent.
    return key(
                jdbc.sql(
                    """
                    UPDATE discovery_chunk SET
                      completed_at      = :now,
                      pages_fetched     = :pagesFetched,
                      notices_published = :noticesPublished
                    WHERE cycle_date = :cycleDate AND publication_date = :publicationDate
                      AND modality_code = :modalityCode AND state = :state
                      AND completed_at IS NULL
                    """),
                chunk)
            .param("now", utc(now))
            .param("pagesFetched", pagesFetched)
            .param("noticesPublished", noticesPublished)
            .update()
        == 1;
  }

  @Override
  public void recordFailure(DiscoveryChunk chunk, Instant now, String reason) {
    key(
            jdbc.sql(
                """
                UPDATE discovery_chunk SET last_failure_at = :now, last_failure = :reason
                WHERE cycle_date = :cycleDate AND publication_date = :publicationDate
                  AND modality_code = :modalityCode AND state = :state
                """),
            chunk)
        .param("now", utc(now))
        .param("reason", reason)
        .update();
  }

  @Override
  public List<CoverageGap> detectGaps(LocalDate windowStart, Instant now) {
    // The series is generated from the calendar rather than read from the rows. A service that was
    // down for a week never planned those dates, so a check that only looked at existing rows
    // would report nothing and lose the days in silence -- which is the failure the lookback
    // exists to survive.
    //
    // ON CONFLICT DO NOTHING with RETURNING is what makes the alert fire exactly once: only rows
    // actually inserted come back, and no read-then-write window exists for a concurrent run to
    // slip into.
    return jdbc.sql(
            """
            WITH responsibility AS (
              SELECT modality_code, state, min(publication_date) AS since
              FROM discovery_chunk
              WHERE origin = 'SCHEDULED'
              GROUP BY modality_code, state
            ),
            expected AS (
              SELECT r.modality_code, r.state, d::date AS publication_date
              FROM responsibility r
              CROSS JOIN LATERAL
                generate_series(r.since, (:windowStart::date - 1), interval '1 day') AS d
            )
            INSERT INTO discovery_coverage_gap (
              publication_date, modality_code, state, detected_at)
            SELECT e.publication_date, e.modality_code, e.state, :now
            FROM expected e
            WHERE NOT EXISTS (
              SELECT 1 FROM discovery_chunk covered
              WHERE covered.publication_date = e.publication_date
                AND covered.modality_code    = e.modality_code
                AND covered.state            = e.state
                AND covered.completed_at IS NOT NULL
                AND covered.cycle_date > covered.publication_date
            )
            ON CONFLICT (publication_date, modality_code, state) DO NOTHING
            RETURNING publication_date, modality_code, state
            """)
        .param("windowStart", windowStart)
        .param("now", utc(now))
        .query(JdbcDiscoveryChunkRepository::toGap)
        .list();
  }

  @Override
  public void resolveGap(
      LocalDate publicationDate, int modalityCode, BrazilianState state, Instant now) {
    jdbc.sql(
            """
            UPDATE discovery_coverage_gap SET resolved_at = :now
            WHERE publication_date = :publicationDate AND modality_code = :modalityCode
              AND state = :state AND resolved_at IS NULL
            """)
        .param("publicationDate", publicationDate)
        .param("modalityCode", modalityCode)
        .param("state", state.name())
        .param("now", utc(now))
        .update();
  }

  @Override
  public List<CoverageGap> openGaps() {
    return jdbc.sql(
            """
            SELECT publication_date, modality_code, state
            FROM discovery_coverage_gap
            WHERE resolved_at IS NULL
            ORDER BY publication_date, modality_code
            """)
        .query(JdbcDiscoveryChunkRepository::toGap)
        .list();
  }

  private static JdbcClient.StatementSpec key(
      JdbcClient.StatementSpec statement, DiscoveryChunk chunk) {
    return statement
        .param("cycleDate", chunk.cycleDate())
        .param("publicationDate", chunk.publicationDate())
        .param("modalityCode", chunk.modalityCode())
        .param("state", chunk.state().name());
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static DiscoveryChunk toChunk(ResultSet row, int rowNumber) throws SQLException {
    return new DiscoveryChunk(
        row.getObject("cycle_date", LocalDate.class),
        row.getObject("publication_date", LocalDate.class),
        row.getInt("modality_code"),
        BrazilianState.valueOf(row.getString("state")),
        ChunkOrigin.valueOf(row.getString("origin")),
        row.getInt("attempts"));
  }

  private static CoverageGap toGap(ResultSet row, int rowNumber) throws SQLException {
    return new CoverageGap(
        row.getObject("publication_date", LocalDate.class),
        row.getInt("modality_code"),
        BrazilianState.valueOf(row.getString("state")));
  }
}
