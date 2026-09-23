package io.github.furlanettoeduardo.radar.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.ingestion.discovery.ChunkOrigin;
import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunk;
import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunkRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The backfill procedure, run from the file operators will actually run.
 *
 * <p>The script is read off disk rather than retyped here, for the same reason the migrations are:
 * a copy in a test proves the copy works. An operational script that has silently stopped matching
 * its schema is discovered at the worst possible moment, which is the moment somebody reaches for
 * it because something has already gone wrong.
 */
@Testcontainers
class DiscoveryBackfillScriptIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;
  private static String backfillScript;

  @BeforeAll
  static void applyTheRealMigrations() throws IOException {
    dataSource = SchemaFixture.migrated(POSTGRES);
    backfillScript = Files.readString(operationsScript(), StandardCharsets.UTF_8);
  }

  @BeforeEach
  void startFromAnEmptyTable() {
    JdbcClient.create(dataSource)
        .sql("TRUNCATE TABLE discovery_chunk, discovery_coverage_gap")
        .update();
  }

  private void runBackfillFor(LocalDate publicationDate, int modalityCode, BrazilianState state) {
    JdbcClient.create(dataSource)
        .sql(backfillScript)
        .param("publication_date", publicationDate)
        .param("modality_code", modalityCode)
        .param("state", state.name())
        .update();
  }

  private DiscoveryChunkRepository repository() {
    return new JdbcDiscoveryChunkRepository(JdbcClient.create(dataSource));
  }

  @Test
  @DisplayName("the backfill records a manual chunk that the next cycle will work")
  void theBackfillRecordsAManualChunk() {
    LocalDate longAgo = LocalDate.of(2026, 5, 4);

    runBackfillFor(longAgo, 6, BrazilianState.SP);

    assertThat(repository().pending(LocalDate.of(2030, 1, 1)))
        .as("a backfill is a standing request, worked by whichever cycle comes next")
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.publicationDate()).isEqualTo(longAgo);
              assertThat(chunk.origin()).isEqualTo(ChunkOrigin.MANUAL);
              assertThat(chunk.attempts()).isZero();
            });
  }

  @Test
  @DisplayName("running it twice is safe: the second run resets the chunk rather than failing")
  void runningItTwiceIsSafe() {
    LocalDate longAgo = LocalDate.of(2026, 5, 4);
    runBackfillFor(longAgo, 6, BrazilianState.SP);
    DiscoveryChunk chunk = repository().pending(LocalDate.of(2030, 1, 1)).get(0);
    repository().complete(chunk, java.time.Instant.now(), 3, 12);

    runBackfillFor(longAgo, 6, BrazilianState.SP);

    assertThat(repository().pending(LocalDate.of(2030, 1, 1)))
        .as("asking again for a date already fetched must ask again, not silently do nothing")
        .hasSize(1);
  }

  @Test
  @DisplayName("a backfilled chunk never becomes a coverage gap")
  void aBackfilledChunkIsNeverAGap() {
    runBackfillFor(LocalDate.of(2020, 1, 1), 6, BrazilianState.SP);

    assertThat(
            repository()
                .detectGaps(
                    LocalDate.of(2026, 9, 21),
                    java.util.List.of(6),
                    java.util.Set.of(BrazilianState.SP),
                    java.time.Instant.now()))
        .as("one backfill of an old date must not report every date since as lost")
        .isEmpty();
  }

  private static Path operationsScript() {
    Path relative =
        Path.of(
            "radar-api",
            "src",
            "main",
            "resources",
            "db",
            "operations",
            "backfill-discovery-chunk.sql");
    return Stream.of(Path.of(""), Path.of(".."))
        .map(base -> base.resolve(relative).toAbsolutePath().normalize())
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "could not find the backfill script from " + Path.of("").toAbsolutePath()));
  }
}
