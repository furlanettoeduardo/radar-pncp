package io.github.furlanettoeduardo.radar.domain.matching;

/**
 * How much of the total weight was actually evaluable, as a percentage.
 *
 * <p>Because weights sum to 100, this is simply the sum of the weights of the rules that ran. A
 * procurement whose budget PNCP hid scores 85 on 85 percent of the criteria rather than carrying an
 * invisible 15 point penalty, and the difference is the entire reason this exists.
 */
public record EvidenceCoverage(int percent) {

  public EvidenceCoverage {
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException(
          "evidence coverage must be within [0,100] but was " + percent);
    }
  }

  public static EvidenceCoverage of(int percent) {
    return new EvidenceCoverage(percent);
  }
}
