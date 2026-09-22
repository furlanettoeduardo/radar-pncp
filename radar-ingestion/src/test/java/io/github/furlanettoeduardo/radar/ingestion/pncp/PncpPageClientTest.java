package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The single page fetch, against a stubbed PNCP fed from the recorded samples. */
class PncpPageClientTest {

  private static final String PUBLICATION_PATH = "/v1/contratacoes/publicacao";
  private static final LocalDate FROM = LocalDate.of(2026, 9, 15);
  private static final LocalDate TO = LocalDate.of(2026, 9, 21);

  @RegisterExtension
  static final WireMockExtension PNCP =
      WireMockExtension.newInstance().options(options().dynamicPort()).build();

  private PncpPageClient clientWith(PncpProperties properties) {
    return new PncpPageClient(properties);
  }

  private PncpProperties properties() {
    return new PncpProperties(
        PNCP.baseUrl(),
        List.of(6),
        10,
        1000,
        8,
        Duration.ofSeconds(2),
        Duration.ofMillis(500),
        Duration.ofSeconds(30),
        "radar-pncp/0.1.0 (+https://github.com/furlanettoeduardo/radar-pncp)");
  }

  private PncpPageRequest firstPage() {
    return new PncpPageRequest(FROM, TO, Optional.of(BrazilianState.SP), 6, 1);
  }

  @Test
  @DisplayName("reads a page of notices and the page count PNCP reports")
  void readsAPageOfNotices() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SampleFixtures.read("contratacoes-publicacao.json"))));

    PncpPage page = clientWith(properties()).fetch(firstPage());

    assertThat(page.notices()).hasSize(3);
    assertThat(page.totalRecords()).isEqualTo(1697);
    assertThat(page.totalPages()).isEqualTo(170);
    assertThat(page.pageNumber()).isEqualTo(1);
    assertThat(page.notices().get(0).get("numeroControlePNCP").asText())
        .isEqualTo("00394429000100-1-002331/2026");
  }

  @Test
  @DisplayName("sends the parameters PNCP requires, and says who is calling")
  void sendsTheRequiredParametersAndIdentifiesItself() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SampleFixtures.read("contratacoes-publicacao.json"))));

    clientWith(properties()).fetch(firstPage());

    PNCP.verify(
        getRequestedFor(urlPathEqualTo(PUBLICATION_PATH))
            .withQueryParam("dataInicial", equalTo("20260915"))
            .withQueryParam("dataFinal", equalTo("20260921"))
            .withQueryParam("codigoModalidadeContratacao", equalTo("6"))
            .withQueryParam("uf", equalTo("SP"))
            .withQueryParam("pagina", equalTo("1"))
            .withQueryParam("tamanhoPagina", equalTo("10"))
            .withHeader("User-Agent", equalTo(properties().userAgent())));
  }

  @Test
  @DisplayName("omits the state parameter when the query covers every state")
  void omitsTheStateWhenAskingForEverywhere() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SampleFixtures.read("contratacoes-publicacao.json"))));

    clientWith(properties()).fetch(new PncpPageRequest(FROM, TO, Optional.empty(), 6, 1));

    PNCP.verify(
        getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)).withQueryParam("uf", absentQueryParam()));
  }

  @Test
  @DisplayName("a 204 with no body is an empty page, not a failure: PNCP answers that way")
  void treatsNoContentAsAnEmptyPage() {
    PNCP.stubFor(get(urlPathEqualTo(PUBLICATION_PATH)).willReturn(aResponse().withStatus(204)));

    PncpPage page = clientWith(properties()).fetch(firstPage());

    assertThat(page.notices()).isEmpty();
    assertThat(page.isEmpty()).isTrue();
    PNCP.verify(1, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName("retries a 500 and succeeds, and the retry is asserted by request count")
  void retriesServerErrors() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("recovered"));
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .inScenario("flaky")
            .whenScenarioStateIs("recovered")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SampleFixtures.read("contratacoes-publicacao.json"))));

    PncpPage page = clientWith(properties()).fetch(firstPage());

    assertThat(page.notices()).hasSize(3);
    // The point of this test: a retry that silently never fires would pass on the result alone.
    PNCP.verify(2, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName("a 404 is not retried: asking again will not make the resource exist")
  void doesNotRetryClientErrors() {
    PNCP.stubFor(get(urlPathEqualTo(PUBLICATION_PATH)).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> clientWith(properties()).fetch(firstPage()))
        .isInstanceOf(PncpRequestRejectedException.class)
        .hasMessageContaining("404");

    PNCP.verify(1, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName("a 400 carries the PNCP error message, which names the missing parameter")
  void surfacesTheApiErrorMessage() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SampleFixtures.read("caso-erro.json"))));

    assertThatThrownBy(() -> clientWith(properties()).fetch(firstPage()))
        .isInstanceOf(PncpRequestRejectedException.class)
        .hasMessageContaining("codigoModalidadeContratacao");
  }

  @Test
  @DisplayName(
      "a body that is not the expected shape fails without retrying: retrying cannot fix it")
  void doesNotRetryAMalformedBody() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"unexpected\":true")));

    assertThatThrownBy(() -> clientWith(properties()).fetch(firstPage()))
        .isInstanceOf(PncpMalformedResponseException.class);

    PNCP.verify(1, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName("a read timeout is retried, because it is a transient failure")
  void retriesTimeouts() {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .inScenario("slow")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withFixedDelay(1500))
            .willSetStateTo("fast"));
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .inScenario("slow")
            .whenScenarioStateIs("fast")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SampleFixtures.read("contratacoes-publicacao.json"))));

    PncpPage page = clientWith(properties()).fetch(firstPage());

    assertThat(page.notices()).hasSize(3);
    PNCP.verify(2, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName("gives up after the configured attempts rather than retrying forever")
  void givesUpEventually() {
    PNCP.stubFor(get(urlPathEqualTo(PUBLICATION_PATH)).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> clientWith(properties()).fetch(firstPage()))
        .isInstanceOf(PncpUnavailableException.class);

    PNCP.verify(3, getRequestedFor(urlPathEqualTo(PUBLICATION_PATH)));
  }

  @Test
  @DisplayName("an open circuit surfaces as the adapter's own exception, not a Resilience4j one")
  void anOpenCircuitIsReportedAsUnavailable() {
    PNCP.stubFor(get(urlPathEqualTo(PUBLICATION_PATH)).willReturn(aResponse().withStatus(503)));
    PncpPageClient client = clientWith(properties());

    // Drive the breaker open: each fetch is three recorded calls, and it opens at ten.
    for (int attempt = 0; attempt < 4; attempt++) {
      assertThatThrownBy(() -> client.fetch(firstPage()))
          .isInstanceOf(PncpUnavailableException.class);
    }
    int requestsBeforeTheCircuitOpened = PNCP.getServeEvents().getRequests().size();

    assertThatThrownBy(() -> client.fetch(firstPage()))
        .isInstanceOf(PncpUnavailableException.class)
        .hasMessageContaining("circuit breaker is open");

    // Fails fast: an open circuit must not reach PNCP at all.
    assertThat(PNCP.getServeEvents().getRequests()).hasSize(requestsBeforeTheCircuitOpened);
  }

  private static com.github.tomakehurst.wiremock.matching.StringValuePattern absentQueryParam() {
    return com.github.tomakehurst.wiremock.client.WireMock.absent();
  }
}
