package io.github.furlanettoeduardo.radar.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Smoke test: the ingestion context starts and exposes the two management endpoints we allow. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RadarIngestionApplicationTest {

  private final TestRestTemplate restTemplate;

  RadarIngestionApplicationTest(@Autowired TestRestTemplate restTemplate) {
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
