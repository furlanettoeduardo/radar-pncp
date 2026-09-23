package io.github.furlanettoeduardo.radar.ingestion.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Applies radar-api's own migration files to a container.
 *
 * <p>The files are read off the filesystem rather than copied here. That module owns the schema and
 * is the only process that migrates at startup; if its DDL and this module's SQL ever disagree,
 * this build fails instead of the consumer failing at three in the morning.
 */
final class SchemaFixture {

  private SchemaFixture() {}

  static DataSource migrated(PostgreSQLContainer<?> postgres) {
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(postgres.getJdbcUrl());
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());

    Flyway.configure()
        .dataSource(source)
        .locations("filesystem:" + migrationsOwnedByTheApiModule())
        .load()
        .migrate();
    return source;
  }

  /**
   * Resolves the migration directory from whichever directory the build runs in. Maven sets it to
   * the module, an IDE often sets it to the repository root, and a location that silently resolves
   * to nothing would leave Flyway reporting a clean success over an empty schema.
   */
  private static Path migrationsOwnedByTheApiModule() {
    Path relative = Path.of("radar-api", "src", "main", "resources", "db", "migration");
    return Stream.of(Path.of(""), Path.of(".."))
        .map(base -> base.resolve(relative).toAbsolutePath().normalize())
        .filter(Files::isDirectory)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "could not find radar-api's migrations from "
                        + Path.of("").toAbsolutePath()
                        + ". These tests apply the schema its owner defines; they do not carry a"
                        + " copy."));
  }
}
