package io.github.furlanettoeduardo.radar.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepositoryContract;
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
 * The JDBC adapter, held to the same contract as the in-memory fake, against a real PostgreSQL 16.
 *
 * <p>The migrations applied here are {@code radar-api}'s own files, read off the filesystem rather
 * than copied. That module owns the schema and is the only process that migrates at startup; if its
 * DDL and this adapter's SQL ever disagree, this build fails rather than the consumer failing at
 * three in the morning.
 *
 * <p>Spring is deliberately absent. What is under test is the SQL, and a context would add a
 * datasource, a health indicator and a component scan between the test and the thing it is about.
 * {@code RadarIngestionApplicationIT} covers the wiring.
 */
@Testcontainers
@DisplayName("JDBC ProcurementRepository")
class JdbcProcurementRepositoryIT extends ProcurementRepositoryContract {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;

  @BeforeAll
  static void applyTheRealMigrations() {
    dataSource = SchemaFixture.migrated(POSTGRES);
  }

  @BeforeEach
  void startFromAnEmptyTable() {
    JdbcClient.create(dataSource).sql("TRUNCATE TABLE procurement").update();
  }

  @Override
  protected ProcurementRepository repository() {
    return new JdbcProcurementRepository(JdbcClient.create(dataSource));
  }

  @Test
  @DisplayName("the migration is what creates the table, not this test")
  void theSchemaComesFromTheOwningModule() {
    Integer applied =
        JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM flyway_schema_history WHERE success = true")
            .query(Integer.class)
            .single();

    assertThat(applied)
        .as("if this is zero the location below silently pointed at nothing")
        .isPositive();
  }
}
