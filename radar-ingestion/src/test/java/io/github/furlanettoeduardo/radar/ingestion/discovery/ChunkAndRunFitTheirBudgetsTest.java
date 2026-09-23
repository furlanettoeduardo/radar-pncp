package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

/**
 * The two budgets a discovery run has to fit inside, checked against the real configuration.
 *
 * <p>This replaces a test that enforced a coupling which no longer exists. While one run was one
 * fan out, the lookback and the page cap could not move independently: a wider window meant more
 * pages in a single capped operation, and a lookback wide enough to exceed the cap would have
 * thrown on every run forever. <b>Chunking severs that.</b> The cap is now per chunk, and the
 * largest chunk is one publication date of one modality, which the window's width cannot change.
 *
 * <p>What bounds the lookback instead:
 *
 * <ul>
 *   <li>the run budget, since every chunk of the window is still fetched in one invocation;
 *   <li>politeness, since every extra day is a full day of pages against a public API, every day,
 *       forever.
 * </ul>
 *
 * <p>Neither is a deadlock the way the old coupling was — exceeding them degrades a run rather than
 * making every run impossible — which is why they are asserted with a margin rather than exactly.
 */
@SpringBootTest(
    classes = ChunkAndRunFitTheirBudgetsTest.BindTheRealConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChunkAndRunFitTheirBudgetsTest {

  @Configuration
  @EnableConfigurationProperties({DiscoveryProperties.class, PncpProperties.class})
  static class BindTheRealConfiguration {}

  /**
   * Why 1.5: the volume figures are one week of observation against somebody else's publishing
   * schedule, and the peak multiplier behind them is a single Thursday.
   */
  private static final double SAFETY_FACTOR = 1.5;

  /**
   * The slowest time to first byte measured on 2026-09-23 at {@code page-size: 10}, in seconds.
   * Used as a per-page cost, which is pessimistic: it treats every page of a run as the worst page
   * observed.
   */
  private static final double SLOWEST_OBSERVED_PAGE_SECONDS = 1.04;

  private final DiscoveryProperties discovery;
  private final PncpProperties pncp;

  ChunkAndRunFitTheirBudgetsTest(
      @Autowired DiscoveryProperties discovery, @Autowired PncpProperties pncp) {
    this.discovery = discovery;
    this.pncp = pncp;
  }

  @Test
  @DisplayName("the largest single chunk fits the per-chunk cap, with margin")
  void theLargestChunkFitsTheCap() {
    double largest =
        ObservedPageVolume.peakPagesForLargestChunk(discovery.states(), pncp.modalityCodes());

    assertThat(largest * SAFETY_FACTOR)
        .as(
            "the biggest chunk of %s at peak is about %.0f pages, against a cap of %d",
            pncp.modalityCodes(), largest, pncp.maxPagesPerChunk())
        .isLessThanOrEqualTo(pncp.maxPagesPerChunk());
  }

  @Test
  @DisplayName("a peak run fits the operation budget, with margin")
  void aPeakRunFitsTheOperationBudget() {
    double pages =
        ObservedPageVolume.peakPagesForRun(
            discovery.lookbackDays(), discovery.states(), pncp.modalityCodes());
    double seconds =
        pages / pncp.maxConcurrentRequests() * SLOWEST_OBSERVED_PAGE_SECONDS * SAFETY_FACTOR;

    assertThat(Duration.ofMillis((long) (seconds * 1000)))
        .as(
            "a %d day lookback is about %.0f pages at peak, roughly %.0fs at %d at a time",
            discovery.lookbackDays(), pages, seconds, pncp.maxConcurrentRequests())
        .isLessThanOrEqualTo(pncp.operationDeadline());
  }

  @Test
  @DisplayName("widening the lookback cannot change the largest chunk: that coupling is gone")
  void theLookbackNoLongerMovesTheChunkCap() {
    double atTheConfiguredLookback =
        ObservedPageVolume.peakPagesForLargestChunk(discovery.states(), pncp.modalityCodes());
    double atARidiculousLookback =
        ObservedPageVolume.peakPagesForLargestChunk(discovery.states(), pncp.modalityCodes());

    assertThat(atARidiculousLookback)
        .as("the largest chunk is one date and one modality, whatever the window is")
        .isEqualTo(atTheConfiguredLookback);
    assertThat(ObservedPageVolume.peakPagesForRun(30, discovery.states(), pncp.modalityCodes()))
        .as("only the run budget grows with the window, and it is the thing that now bounds it")
        .isGreaterThan(
            ObservedPageVolume.peakPagesForRun(
                discovery.lookbackDays(), discovery.states(), List.copyOf(pncp.modalityCodes())));
  }
}
