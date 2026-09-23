package io.github.furlanettoeduardo.radar.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the ingestion context starts and exposes the two management endpoints we allow.
 *
 * <p>An integration test rather than a unit test since this service gained a repository. It needs a
 * real database to start at all, and the health endpoint it asserts on is only meaningful when
 * there is one to report on. A version of this that started the context without a database would be
 * proving that a configuration which cannot run in production can be made to start in a test.
 *
 * <p>Flyway is off here exactly as it is in production, so the schema is applied the way this
 * module will always meet it: created by somebody else, beforehand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RadarIngestionApplicationIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private final TestRestTemplate restTemplate;

  RadarIngestionApplicationIT(@Autowired TestRestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  @Test
  @DisplayName("actuator health reports the service as UP")
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
    assertThat(response.getBody()).contains("\"artifact\":\"radar-ingestion\"");
  }

  @Test
  @DisplayName("endpoints we did not expose stay closed")
  void unexposedEndpointsAreNotReachable() {
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/env", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
