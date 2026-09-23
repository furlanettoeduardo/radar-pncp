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
 * the application actually loads, and it costs what the <em>configured</em> scope costs. An earlier
 * version asserted against a national extrapolation while the application was configured for one
 * state, which gated a working configuration on a number describing a different system. National
 * scope is a real limit and it is recorded as one, in ADR 0010 and in {@link
 * ObservedPageVolumeTest}, rather than here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DiscoveryWindowFitsTheFanOutCapTest {

  /**
   * Why 1.5 and not 1.0: the volume figures are a single week of observation against an API whose
   * output is somebody else's publishing schedule. A cap that is only reached on a busy Tuesday is
   * a cap that fails unpredictably, which is worse than one that fails always.
   */
  private static final double SAFETY_FACTOR = 1.5;

  private final DiscoveryProperties discovery;
  private final PncpProperties pncp;

  DiscoveryWindowFitsTheFanOutCapTest(
      @Autowired DiscoveryProperties discovery, @Autowired PncpProperties pncp) {
    this.discovery = discovery;
    this.pncp = pncp;
  }

  @Test
  @DisplayName("the configured window and scope cannot need more pages than the cap allows")
  void theConfiguredWindowFitsWithinTheCap() {
    double needed =
        ObservedPageVolume.pagesForRun(
            discovery.lookbackDays(), discovery.states(), pncp.modalityCodes());

    assertThat(needed)
        .as(
            "a %d day lookback over %s for modalities %s needs about %.0f pages, against a cap of"
                + " %d",
            discovery.lookbackDays(),
            discovery.states().isEmpty() ? "every state" : discovery.states(),
            pncp.modalityCodes(),
            needed,
            pncp.maxTotalPages())
        .isLessThanOrEqualTo(pncp.maxTotalPages());
  }

  @Test
  @DisplayName("and it keeps a margin, because one week of volume is not a guarantee")
  void theConfiguredWindowKeepsAMargin() {
    double needed =
        ObservedPageVolume.pagesForRun(
            discovery.lookbackDays(), discovery.states(), pncp.modalityCodes());

    assertThat(needed * SAFETY_FACTOR)
        .as(
            "%.0f pages leaves no %sx margin under a cap of %d",
            needed, SAFETY_FACTOR, pncp.maxTotalPages())
        .isLessThanOrEqualTo(pncp.maxTotalPages());
  }
}
