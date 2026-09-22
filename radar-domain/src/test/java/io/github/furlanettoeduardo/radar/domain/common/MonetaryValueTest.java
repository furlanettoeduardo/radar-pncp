package io.github.furlanettoeduardo.radar.domain.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonetaryValueTest {

  @Test
  @DisplayName("the same amount written with different scales is the same amount")
  void scaleDoesNotChangeEquality() {
    assertThat(MonetaryValue.of("10000")).isEqualTo(MonetaryValue.of("10000.00"));
    assertThat(MonetaryValue.of("1.5")).isEqualTo(MonetaryValue.of("1.50"));
    assertThat(MonetaryValue.of("0")).isEqualTo(MonetaryValue.of("0.00"));
  }

  @Test
  @DisplayName("equal values hash equally, or every set and map key silently misbehaves")
  void equalValuesHashEqually() {
    assertThat(MonetaryValue.of("10000")).hasSameHashCodeAs(MonetaryValue.of("10000.00"));
    // A HashSet rather than Set.of, which rejects duplicates outright instead of collapsing them.
    assertThat(new HashSet<>(List.of(MonetaryValue.of("10000"), MonetaryValue.of("10000.00"))))
        .hasSize(1);
  }

  @Test
  @DisplayName("scientific notation is the same amount as the plain form")
  void scientificNotationIsTheSameAmount() {
    assertThat(new MonetaryValue(new BigDecimal("1E+4"))).isEqualTo(MonetaryValue.of("10000"));
  }

  @Test
  @DisplayName("comparison agrees with equality, as Comparable requires")
  void comparisonAgreesWithEquality() {
    assertThat(MonetaryValue.of("10000")).isEqualByComparingTo(MonetaryValue.of("10000.00"));
    assertThat(MonetaryValue.of("10000")).isLessThan(MonetaryValue.of("10000.01"));
  }

  @Test
  @DisplayName("different amounts stay different")
  void differentAmountsStayDifferent() {
    assertThat(MonetaryValue.of("10000")).isNotEqualTo(MonetaryValue.of("10000.01"));
  }

  @Test
  @DisplayName("renders plainly, so a reason string never shows a user 1E+4")
  void rendersPlainly() {
    assertThat(new MonetaryValue(new BigDecimal("1E+4")).amount().toString()).isEqualTo("10000");
    assertThat(MonetaryValue.of("14785.32").amount().toString()).isEqualTo("14785.32");
  }

  @Test
  @DisplayName("refuses a negative amount")
  void refusesNegatives() {
    assertThatIllegalArgumentException().isThrownBy(() -> MonetaryValue.of("-1"));
  }
}
