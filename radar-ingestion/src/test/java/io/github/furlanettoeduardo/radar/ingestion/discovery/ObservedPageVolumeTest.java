package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind the fan out margin, tested on its own so the check against the real
 * configuration can stay a single assertion.
 *
 * <p>The refusal tests matter more than the sums. They are what stops a future change widening the
 * configured scope to something nobody has ever measured and finding out what it costs from
 * production.
 */
class ObservedPageVolumeTest {

  private static final Set<BrazilianState> SP = Set.of(BrazilianState.SP);
  private static final Set<BrazilianState> EVERYWHERE = Set.of();

  @Test
  @DisplayName("pages per day for one state is the sum of the configured modalities")
  void pagesPerDayIsTheSumOfTheConfiguredModalities() {
    assertThat(ObservedPageVolume.pagesPerDay(SP, List.of(6))).isCloseTo(24.43, within(0.01));
    assertThat(ObservedPageVolume.pagesPerDay(SP, List.of(6, 8))).isCloseTo(73.57, within(0.01));
  }

  @Test
  @DisplayName("an empty state set means nationally, which costs the whole multiplier")
  void anEmptyStateSetMeansNationally() {
    assertThat(ObservedPageVolume.pagesPerDay(EVERYWHERE, List.of(6)))
        .as("24.4 SP pages a day against the pessimistic national multiplier")
        .isCloseTo(146.57, within(0.01));
  }

  @Test
  @DisplayName("a run covers the lookback plus today, because PNCP date bounds are inclusive")
  void aRunCoversTheLookbackPlusToday() {
    assertThat(ObservedPageVolume.pagesForRun(2, SP, List.of(6)))
        .as("dataInicial=today-2 through dataFinal=today is three calendar days, not two")
        .isCloseTo(73.29, within(0.01));
  }

  @Test
  @DisplayName("a state nobody measured throws rather than borrowing SP's figures")
  void anUnmeasuredStateIsRefused() {
    assertThatThrownBy(() -> ObservedPageVolume.pagesPerDay(Set.of(BrazilianState.RJ), List.of(6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RJ")
        .hasMessageContaining("Measure it before configuring it");
  }

  @Test
  @DisplayName("a modality nobody measured throws too, so adding one cannot silently cost nothing")
  void anUnmeasuredModalityIsRefused() {
    assertThatThrownBy(() -> ObservedPageVolume.pagesPerDay(SP, List.of(99)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("modality 99")
        .hasMessageContaining("Measure it before configuring it");
  }

  @Test
  @DisplayName("the design limit: national scope over every modality cannot fit any sane cap")
  void nationalScopeOverEveryModalityExceedsTheCap() {
    double pages = ObservedPageVolume.pagesForRun(2, EVERYWHERE, List.of(4, 6, 8));

    assertThat(pages)
        .as("the number that says the invocation shape must change, not that the cap must rise")
        .isCloseTo(1396.29, within(0.01));
  }
}
