package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards a configuration deadlock: a lookback window wide enough to need more pages than the fan
 * out cap allows would throw on <em>every</em> run, forever, and never once succeed.
 *
 * <p>The runtime failure is at least legible — {@code PncpFanOutTooLargeException} says to narrow
 * the date range rather than raise the cap — but discovering that in production, from a scheduled
 * job, is a much worse way to learn it than a red build.
 *
 * <p>This binds the real configuration rather than parsing the YAML, so it cannot drift from what
 * the application actually loads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DiscoveryWindowFitsTheFanOutCapTest {

  /**
   * Pages PNCP returns for one day, nationally, at the configured page size.
   *
   * <p>Derived from measurement, not chosen: {@code contratacoes/publicacao} reported {@code
   * totalPaginas: 170} for one week of SP, and a live run reported 190 for eight days — call it 24
   * pages a day for SP. The national multiplier of roughly 4 to 6 is an estimate from SP's share of
   * Brazilian municipalities, so 160 is the pessimistic end of it.
   *
   * <p>If PNCP volume grows, this constant is wrong before the cap is, which is why the assertion
   * below leaves headroom rather than asserting the exact boundary.
   */
  private static final int PESSIMISTIC_PAGES_PER_DAY = 160;

  private final DiscoveryProperties discovery;
  private final PncpProperties pncp;

  DiscoveryWindowFitsTheFanOutCapTest(
      @Autowired DiscoveryProperties discovery, @Autowired PncpProperties pncp) {
    this.discovery = discovery;
    this.pncp = pncp;
  }

  @Test
  @DisplayName("the default lookback window cannot need more pages than the fan out cap allows")
  void theDefaultWindowFitsWithinTheCap() {
    int pagesAWorstCaseRunWouldNeed = discovery.lookbackDays() * PESSIMISTIC_PAGES_PER_DAY;

    assertThat(pagesAWorstCaseRunWouldNeed)
        .as(
            "a %d day lookback needs about %d pages at %d a day, against a cap of %d",
            discovery.lookbackDays(),
            pagesAWorstCaseRunWouldNeed,
            PESSIMISTIC_PAGES_PER_DAY,
            pncp.maxTotalPages())
        .isLessThanOrEqualTo(pncp.maxTotalPages());
  }

  @Test
  @DisplayName("and it keeps a margin, because the volume estimate is an estimate")
  void theDefaultWindowKeepsAMargin() {
    int pagesAWorstCaseRunWouldNeed = discovery.lookbackDays() * PESSIMISTIC_PAGES_PER_DAY;

    assertThat(pagesAWorstCaseRunWouldNeed * 1.5)
        .as("a cap reached only on a busy day is a cap that fails unpredictably")
        .isLessThanOrEqualTo(pncp.maxTotalPages());
  }
}
