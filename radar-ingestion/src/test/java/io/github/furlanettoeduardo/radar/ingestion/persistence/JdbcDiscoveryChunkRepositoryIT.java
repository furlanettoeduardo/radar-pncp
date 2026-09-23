package io.github.furlanettoeduardo.radar.ingestion.persistence;

import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunkRepository;
import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunkRepositoryContract;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The chunk store, held to the same contract as the fake, against a real PostgreSQL 16.
 *
 * <p>The gap detection is the reason this matters more than most adapter tests. Its calendar walk
 * is a recursive-looking SQL query that no unit test can stand in for, and it is the only thing
 * standing between a week of downtime and a week of notices disappearing unremarked.
 */
@Testcontainers
@DisplayName("JDBC DiscoveryChunkRepository")
class JdbcDiscoveryChunkRepositoryIT extends DiscoveryChunkRepositoryContract {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;

  @BeforeAll
  static void applyTheRealMigrations() {
    dataSource = SchemaFixture.migrated(POSTGRES);
  }

  @BeforeEach
  void startFromAnEmptyTable() {
    JdbcClient.create(dataSource)
        .sql("TRUNCATE TABLE discovery_chunk, discovery_coverage_gap")
        .update();
  }

  @Override
  protected DiscoveryChunkRepository repository() {
    return new JdbcDiscoveryChunkRepository(JdbcClient.create(dataSource));
  }
}
