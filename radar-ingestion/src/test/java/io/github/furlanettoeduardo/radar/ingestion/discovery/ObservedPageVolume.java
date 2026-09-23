package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How many PNCP pages a discovery run actually costs, from figures somebody went and collected.
 *
 * <p>This exists so the fan out margin check can be one assertion against the real configuration
 * rather than a constant nobody can trace. Every number below carries where it came from.
 *
 * <p><strong>It refuses to estimate.</strong> A state or a modality that has never been observed
 * makes this throw, so widening the configured scope fails the build rather than quietly
 * multiplying something plausible. The version this replaced extrapolated a national figure from
 * SP's share of Brazilian municipalities, and an extrapolation that survives one review starts
 * reading like evidence.
 *
 * <p>The one place estimation is still allowed is the national multiplier, and only because the
 * design limit it produces is a limit rather than a licence: it is used to show that national scope
 * does <em>not</em> fit, never to justify a configuration that does.
 */
final class ObservedPageVolume {

  /**
   * Whether a figure was counted or merely bounded from above. Worst case arithmetic takes both.
   */
  enum Basis {
    MEASURED,
    UPPER_BOUND
  }

  record Observation(double pagesPerDay, Basis basis, String evidence) {}

  /**
   * SP pages per day at {@code page-size: 10}, by modality code.
   *
   * <p>Modalities 6 and 8 were counted over the same seven day window, 2026-09-15 to 2026-09-21,
   * which is why their totals are comparable. Modality 4 is an upper bound rather than a count: the
   * seven day query failed four times against a degraded PNCP, so the figure comes from a single
   * Thursday and is rounded up.
   */
  private static final Map<Integer, Observation> SP_BY_MODALITY =
      Map.of(
          4,
              new Observation(
                  4.0,
                  Basis.UPPER_BOUND,
                  "38 records on 2026-09-17 alone; the seven day window failed four times"),
          6,
              new Observation(
                  171.0 / 7,
                  Basis.MEASURED,
                  "1,707 records over 171 pages, SP, 2026-09-15..21, measured 2026-09-23"),
          8,
              new Observation(
                  344.0 / 7,
                  Basis.MEASURED,
                  "3,438 records over 344 pages, SP, 2026-09-15..21, measured 2026-09-23"));

  /**
   * The pessimistic end of SP's share of Brazilian procurement, used only to show that national
   * scope overruns the cap. It is an estimate from SP's share of municipalities and has never been
   * measured, which is precisely why nothing that must pass is allowed to depend on it.
   */
  static final double NATIONAL_MULTIPLIER = 6.0;

  /**
   * How much busier a peak day is than the weekly average.
   *
   * <p>Measured once: modality 8 published 707 records on Thursday 2026-09-17 against a weekly
   * average of 491 a day over 2026-09-15..21, which is 1.44. A run covers three or four recent
   * calendar days and three consecutive weekdays is the ordinary case, so the average is the wrong
   * figure to size anything against — weekends are in it and a run usually is not.
   *
   * <p>One observation of one modality. It is an estimate, and the honest kind: it is applied to
   * make every budget larger, never to justify one that already fits.
   */
  static final double PEAK_MULTIPLIER = 1.44;

  private ObservedPageVolume() {}

  /**
   * The biggest single chunk, which is what the per-chunk cap has to accommodate.
   *
   * <p>A chunk is one publication date, one modality and one state, so neither the number of
   * configured states nor the width of the window multiplies into it. That is the whole reason
   * chunking removed the margin problem: the cap now bounds the largest thing that can be attempted
   * rather than the sum of everything attempted.
   */
  static double peakPagesForLargestChunk(Set<BrazilianState> states, List<Integer> modalityCodes) {
    states.forEach(ObservedPageVolume::requireObserved);
    return modalityCodes.stream().mapToDouble(ObservedPageVolume::observe).max().orElse(0)
        * PEAK_MULTIPLIER;
  }

  /** Every chunk of a run, on a day when every modality is at peak. */
  static double peakPagesForRun(
      int lookbackDays, Set<BrazilianState> states, List<Integer> modalityCodes) {
    return pagesForRun(lookbackDays, states, modalityCodes) * PEAK_MULTIPLIER;
  }

  static double pagesPerDay(Set<BrazilianState> states, List<Integer> modalityCodes) {
    double perState = modalityCodes.stream().mapToDouble(ObservedPageVolume::observe).sum();
    if (states.isEmpty()) {
      return perState * NATIONAL_MULTIPLIER;
    }
    states.forEach(ObservedPageVolume::requireObserved);
    return perState * states.size();
  }

  /**
   * Pages a whole run costs.
   *
   * <p>A lookback of N days is N+1 calendar days of pages, not N. {@code dataInicial} and {@code
   * dataFinal} are inclusive bounds — the recorded samples prove it, a single day query sends the
   * same date twice and returns that day — and the job asks for {@code today.minusDays(lookback)}
   * through {@code today}. Counting N days here is the arithmetic error this replaced.
   */
  static double pagesForRun(
      int lookbackDays, Set<BrazilianState> states, List<Integer> modalityCodes) {
    return pagesPerDay(states, modalityCodes) * (lookbackDays + 1);
  }

  private static double observe(int modalityCode) {
    Observation observation = SP_BY_MODALITY.get(modalityCode);
    if (observation == null) {
      throw new IllegalArgumentException(
          ("no page volume has ever been observed for modality %d, so what it would cost is "
                  + "unknown. Measure it before configuring it.")
              .formatted(modalityCode));
    }
    return observation.pagesPerDay();
  }

  private static void requireObserved(BrazilianState state) {
    if (state != BrazilianState.SP) {
      throw new IllegalArgumentException(
          ("no page volume has ever been observed for %s, and SP's figures are not a stand in for "
                  + "it. Measure it before configuring it.")
              .formatted(state));
    }
  }
}
