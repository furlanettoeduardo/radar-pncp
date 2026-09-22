package io.github.furlanettoeduardo.radar.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the foundation end to end against a real PostgreSQL 16: the context starts, Flyway applies
 * the migrations, and the management endpoints answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RadarApiApplicationIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private final TestRestTemplate restTemplate;
  private final JdbcTemplate jdbcTemplate;

  RadarApiApplicationIT(
      @Autowired TestRestTemplate restTemplate, @Autowired JdbcTemplate jdbcTemplate) {
    this.restTemplate = restTemplate;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("Flyway applies the baseline migration")
  void flywayAppliesTheBaselineMigration() {
    List<String> applied =
        jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY"
                + " installed_rank",
            String.class);

    assertThat(applied).containsExactly("1");
  }

  @Test
  @DisplayName("the smoke table exists and carries the foundation row")
  void smokeTableCarriesTheFoundationRow() {
    List<String> stages =
        jdbcTemplate.queryForList("SELECT stage FROM schema_version_smoke", String.class);

    assertThat(stages).containsExactly("stage-01-foundation");
  }

  @Test
  @DisplayName("actuator health reports UP including the database")
  void healthEndpointReportsUp() {
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  @DisplayName("actuator info carries the Maven build information")
  void infoEndpointCarriesBuildInformation() {
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"artifact\":\"radar-api\"");
  }
}
