package io.github.furlanettoeduardo.radar.domain.company;

import java.util.Objects;

/**
 * A Brazilian company registration number, held as its fourteen digits.
 *
 * <p>Punctuation is stripped on construction, so a CNPJ typed with dots and a slash equals the same
 * CNPJ typed bare. Only the length is checked: verifying the check digits is a separate concern,
 * and rejecting a registration the government actually issued would be a worse failure than
 * accepting a malformed one.
 */
public record Cnpj(String digits) {

  private static final int LENGTH = 14;

  public Cnpj {
    Objects.requireNonNull(digits, "a CNPJ must have a value");
    digits = digits.replaceAll("[^0-9]", "");
    if (digits.length() != LENGTH) {
      throw new IllegalArgumentException(
          "a CNPJ must carry %d digits but carried %d".formatted(LENGTH, digits.length()));
    }
  }

  public static Cnpj of(String raw) {
    return new Cnpj(raw);
  }
}
