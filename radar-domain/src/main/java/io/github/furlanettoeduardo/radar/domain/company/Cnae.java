package io.github.furlanettoeduardo.radar.domain.company;

import java.util.Objects;

/**
 * A Brazilian economic activity code, as a company declares it, for example {@code 6201-5/01}.
 *
 * <p>The code is kept exactly as given, because it is the company's own data and will be shown
 * back to them. Only the division, its first two digits, carries meaning for this system.
 */
public record Cnae(String code) {

  public Cnae {
    Objects.requireNonNull(code, "a CNAE must have a code");
    if (digitsOf(code).length() < 2) {
      throw new IllegalArgumentException("a CNAE must carry at least a division: " + code);
    }
  }

  public static Cnae of(String code) {
    return new Cnae(code);
  }

  /** The two digit division, which is the level this system maps to a segment. */
  public String division() {
    return digitsOf(code).substring(0, 2);
  }

  private static String digitsOf(String code) {
    return code.replaceAll("[^0-9]", "");
  }
}
