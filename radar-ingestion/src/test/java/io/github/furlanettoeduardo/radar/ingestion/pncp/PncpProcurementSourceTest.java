package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class PncpProcurementSourceTest {

  private static final String PUBLICATION_PATH = "/v1/contratacoes/publicacao";
  private static final ProcurementQuery ONE_WEEK_IN_SP =
      new ProcurementQuery(
          LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 21), Set.of(BrazilianState.SP));

  @RegisterExtension
  static final WireMockExtension PNCP =
      WireMockExtension.newInstance().options(options().dynamicPort()).build();

  private final MeterRegistry meters = new SimpleMeterRegistry();

  private PncpProcurementSource sourceWith(int maxTotalPages) {
    PncpProperties properties =
        new PncpProperties(
            PNCP.baseUrl(),
            List.of(6),
            10,
            maxTotalPages,
            8,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            "radar-pncp/0.1.0 (+https://github.com/furlanettoeduardo/radar-pncp)");
    return new PncpProcurementSource(
        new PncpPageClient(properties), new PncpProcurementMapper(), properties, meters);
  }

  @Test
  @DisplayName("follows the page count PNCP reports and returns every notice across the pages")
  void followsPaginationAcrossPages() {
    stubPage(1, 3, recordedNotices());
    stubPage(2, 3, recordedNotices());
    stubPage(3, 3, recordedNotices());

    List<Procurement> found = sourceWith(500).find(ONE_WEEK_IN_SP);

    assertThat(found).hasSize(9);
    PNCP.verify(3, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
    PNCP.verify(
        getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)).withQueryParam("pagina", equalTo("3")));
  }

  @Test
  @DisplayName("a single page is fetched once, not fetched and then re-fetched")
  void doesNotRefetchASinglePage() {
    stubPage(1, 1, recordedNotices());

    assertThat(sourceWith(500).find(ONE_WEEK_IN_SP)).hasSize(3);
    PNCP.verify(1, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName(
      "a rejected notice does not sink the page, and is counted by the field that caused it")
  void aRejectedNoticeIsCountedNotFatal() {
    ArrayNode notices = recordedNotices();
    ((ObjectNode) notices.get(1)).remove("dataEncerramentoProposta");
    stubPage(1, 1, notices);

    List<Procurement> found = sourceWith(500).find(ONE_WEEK_IN_SP);

    assertThat(found).hasSize(2);
    assertThat(
            meters
                .counter("radar.pncp.notices.rejected", "field", "dataEncerramentoProposta")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a page where every notice is rejected escalates: that is a contract change")
  void escalatesWhenAWholePageIsRejected() {
    ArrayNode notices = recordedNotices();
    notices.forEach(notice -> ((ObjectNode) notice).remove("numeroControlePNCP"));
    stubPage(1, 1, notices);

    assertThatThrownBy(() -> sourceWith(500).find(ONE_WEEK_IN_SP))
        .isInstanceOf(PncpMalformedResponseException.class)
        .hasMessageContaining("every");
  }

  @Test
  @DisplayName("an empty page is not a fully rejected page and must never escalate")
  void anEmptyPageDoesNotEscalate() {
    PNCP.stubFor(get(urlPathEqualTo(PUBLICATION_PATH)).willReturn(aResponse().withStatus(204)));

    assertThat(sourceWith(500).find(ONE_WEEK_IN_SP)).isEmpty();
  }

  @Test
  @DisplayName("refuses to start a fan out larger than the cap, before anything is in flight")
  void refusesAFanOutOverTheCap() {
    stubPage(1, 170, recordedNotices());

    assertThatThrownBy(() -> sourceWith(20).find(ONE_WEEK_IN_SP))
        .isInstanceOf(PncpFanOutTooLargeException.class)
        .hasMessageContaining("170")
        .hasMessageContaining("20");

    // Only the first page was ever requested: the cap fired before page two was submitted.
    PNCP.verify(1, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  private static void stubPage(int page, int totalPages, ArrayNode notices) {
    ObjectNode envelope = PncpJson.mapper().createObjectNode();
    envelope.set("data", notices);
    envelope.put("totalRegistros", notices.size() * totalPages);
    envelope.put("totalPaginas", totalPages);
    envelope.put("numeroPagina", page);
    envelope.put("paginasRestantes", totalPages - page);
    envelope.put("empty", notices.isEmpty());

    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .withQueryParam("pagina", equalTo(String.valueOf(page)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(envelope.toString())));
  }

  private static ArrayNode recordedNotices() {
    try {
      JsonNode envelope =
          PncpJson.mapper().readTree(SampleFixtures.read("contratacoes-publicacao.json"));
      return ((ArrayNode) envelope.get("data")).deepCopy();
    } catch (Exception cause) {
      throw new IllegalStateException("could not read the recorded sample", cause);
    }
  }
}
