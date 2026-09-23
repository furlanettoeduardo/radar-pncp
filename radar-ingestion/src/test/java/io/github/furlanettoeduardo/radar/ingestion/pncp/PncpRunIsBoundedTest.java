package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A scheduled run must not be able to hang.
 *
 * <p>PNCP has been observed accepting a connection and then sending nothing for over a minute, once
 * for nearly seven, and on 2026-09-23 eleven of seventeen calls failed. A daily job that waits that
 * out is worse than one that fails: it holds its lock, overlaps the next trigger, and reports
 * nothing either way.
 *
 * <p>Two different mechanisms bound a run and they act in different places, so both are proved here
 * rather than inferred from the one that is easier to test. {@code retriesTimeouts} in {@link
 * PncpPageClientTest} covers a single page giving up; neither of these does.
 *
 * <p>Timings are small multiples of each other rather than production values, so the suite stays
 * fast and the relationship under test stays visible. What matters is the ordering between them,
 * not the absolute numbers.
 */
class PncpRunIsBoundedTest {

  private static final String PUBLICATION_PATH = "/v1/contratacoes/publicacao";
  private static final ProcurementQuery ONE_WEEK_IN_SP =
      new ProcurementQuery(
          LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 21), Set.of(BrazilianState.SP));

  /** Forty pages of nothing: enough that the second phase cannot possibly finish in time. */
  private static final String FORTY_EMPTY_PAGES =
      """
      {"data":[],"totalRegistros":400,"totalPaginas":40,"numeroPagina":1,"paginasRestantes":39}
      """;

  @RegisterExtension
  static final WireMockExtension PNCP =
      WireMockExtension.newInstance().options(options().dynamicPort()).build();

  private PncpProcurementSource sourceWith(
      Duration readTimeout, Duration operationDeadline, int maxConcurrent) {
    PncpProperties properties =
        new PncpProperties(
            PNCP.baseUrl(),
            List.of(6),
            10,
            500,
            maxConcurrent,
            Duration.ofSeconds(2),
            readTimeout,
            operationDeadline,
            "radar-pncp/0.1.0 (+https://github.com/furlanettoeduardo/radar-pncp)");
    return new PncpProcurementSource(
        new PncpPageClient(properties),
        new PncpProcurementMapper(),
        properties,
        new SimpleMeterRegistry());
  }

  private static void everyPageTakes(int millis) {
    PNCP.stubFor(
        get(urlPathEqualTo(PUBLICATION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(FORTY_EMPTY_PAGES)
                    .withFixedDelay(millis)));
  }

  private static Duration timeOf(Runnable run) {
    long startedAt = System.nanoTime();
    run.run();
    return Duration.ofNanos(System.nanoTime() - startedAt);
  }

  @Test
  @DisplayName("every page hanging past the read timeout ends the run rather than stalling it")
  void aHangPastTheReadTimeoutEndsTheRun() {
    everyPageTakes(10_000);

    // The deadline is far too generous to be what saves this. If the run terminates at all, the
    // read timeout and the retry budget are what terminated it.
    Duration elapsed =
        timeOf(
            () ->
                assertThatThrownBy(
                        () ->
                            sourceWith(Duration.ofMillis(300), Duration.ofMinutes(5), 8)
                                .find(ONE_WEEK_IN_SP))
                    .isInstanceOf(PncpUnavailableException.class));

    assertThat(elapsed)
        .as("three 300ms attempts plus backoff, not the ten seconds the upstream wanted")
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("the deadline bounds the whole run, not each phase of it separately")
  void theDeadlineBoundsTheWholeRunRatherThanEachPhase() {
    // Slow enough to matter, comfortably inside the read timeout so nothing fails and nothing is
    // retried. Only the deadline can end this run.
    everyPageTakes(2_500);

    // Discovery is two phases: one request to learn the page count, then thirty nine more. At one
    // at a time those thirty nine need over ninety seconds, so the deadline decides when this ends.
    Duration elapsed =
        timeOf(
            () ->
                assertThatThrownBy(
                        () ->
                            sourceWith(Duration.ofSeconds(3), Duration.ofSeconds(4), 1)
                                .find(ONE_WEEK_IN_SP))
                    .isInstanceOf(FanOutTimedOutException.class));

    // Phase one costs 2.5s of the 4s budget. If each phase starts the clock again, this run takes
    // 2.5 + 4 = 6.5s and a five minute deadline really means ten. One budget, spent across both.
    assertThat(elapsed)
        .as("a deadline that restarts per phase bounds a run at twice what it says")
        .isLessThan(Duration.ofSeconds(5));
  }
}
