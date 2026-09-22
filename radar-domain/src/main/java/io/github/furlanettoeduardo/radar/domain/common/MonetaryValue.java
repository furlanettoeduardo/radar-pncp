package io.github.furlanettoeduardo.radar.domain.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An amount in Brazilian reais. PNCP publishes every value in BRL, so no currency is carried; the
 * day a second currency appears this record gains a field and every call site is forced to think
 * about it, which is the right outcome.
 *
 * <p>Comparison is numeric rather than by {@code equals}, because {@code BigDecimal} treats {@code
 * 10000} and {@code 10000.00} as different values and a procurement is not cheaper for having been
 * written with two decimal places.
 */
public record MonetaryValue(BigDecimal amount) implements Comparable<MonetaryValue> {

  public MonetaryValue {
    Objects.requireNonNull(amount, "a monetary value must have an amount");
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("a monetary value must not be negative but was " + amount);
    }
    amount = amount.stripTrailingZeros();
    if (amount.scale() < 0) {
      // stripTrailingZeros turns 10000 into 1E+4; bring whole numbers back to a plain scale.
      amount = amount.setScale(0);
    }
  }

  public static MonetaryValue of(String amount) {
    return new MonetaryValue(new BigDecimal(amount));
  }

  @Override
  public int compareTo(MonetaryValue other) {
    return amount.compareTo(other.amount);
  }
}
