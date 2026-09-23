package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which day a discovery run belongs to, and which publication dates it owes.
 *
 * <p>Every date here is a <em>Sao Paulo</em> calendar date, and the difference is not cosmetic. The
 * instance runs in us-east-1 with its clock in UTC, so for three hours every evening the JVM's
 * default zone is already on tomorrow while PNCP, whose {@code dataInicial} and {@code dataFinal}
 * are Brazilian calendar dates, is not. A run at 22:00 in Brasilia that asked for "today" in UTC
 * would ask for a day that has not happened, get nothing, and record that nothing as a successful
 * fetch.
 *
 * <p>The clock is injected for the same reason the scoring engine takes an evaluation instant: a
 * component that reads the clock cannot be tested at the boundary that matters. See ADR 0005.
 */
class DiscoveryCycleTest {

  private static final int LOOKBACK = 3;

  private static Clock at(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }

  @Test
  @DisplayName("at 01:00 UTC the cycle is still the previous day in Sao Paulo")
  void justAfterMidnightInUtcIsStillYesterdayInBrasilia() {
    DiscoveryCycle cycle = DiscoveryCycle.at(at("2026-09-24T01:00:00Z"), LOOKBACK);

    assertThat(cycle.date())
        .as("22:00 on the 23rd in Brasilia: asking for the 24th would ask for a day not yet lived")
        .isEqualTo(LocalDate.of(2026, 9, 23));
  }

  @Test
  @DisplayName("at 23:30 in Sao Paulo the cycle is that day, though UTC has already rolled over")
  void lateEveningInBrasiliaKeepsItsOwnDate() {
    // 23:30 on 2026-09-23 in Sao Paulo is 02:30 on the 24th in UTC.
    DiscoveryCycle cycle = DiscoveryCycle.at(at("2026-09-24T02:30:00Z"), LOOKBACK);

    assertThat(cycle.date()).isEqualTo(LocalDate.of(2026, 9, 23));
  }

  @Test
  @DisplayName("at midday UTC the two calendars agree, which is why the bug hides")
  void middayUtcIsTheSameDayInBothZones() {
    DiscoveryCycle cycle = DiscoveryCycle.at(at("2026-09-24T12:00:00Z"), LOOKBACK);

    assertThat(cycle.date()).isEqualTo(LocalDate.of(2026, 9, 24));
  }

  @Test
  @DisplayName("the window is the lookback plus today, oldest first, because dates are inclusive")
  void theWindowIsTheLookbackPlusToday() {
    DiscoveryCycle cycle = DiscoveryCycle.at(at("2026-09-24T12:00:00Z"), LOOKBACK);

    assertThat(cycle.windowDates())
        .as("a lookback of 3 is four calendar days, and the oldest is the closest to expiring")
        .containsExactly(
            LocalDate.of(2026, 9, 21),
            LocalDate.of(2026, 9, 22),
            LocalDate.of(2026, 9, 23),
            LocalDate.of(2026, 9, 24));
    assertThat(cycle.windowStart()).isEqualTo(LocalDate.of(2026, 9, 21));
  }

  @Test
  @DisplayName("a date is covered only by a cycle that ran after it closed")
  void coverageRequiresACycleAfterTheDateClosed() {
    DiscoveryCycle cycle = DiscoveryCycle.at(at("2026-09-24T12:00:00Z"), LOOKBACK);

    assertThat(cycle.coversWhenCompleted(LocalDate.of(2026, 9, 23)))
        .as("the 23rd closed before this cycle began, so fetching it now settles it")
        .isTrue();
    assertThat(cycle.coversWhenCompleted(LocalDate.of(2026, 9, 24)))
        .as("today is still open: a fetch now is useful, but more will be published after it")
        .isFalse();
  }

  @Test
  @DisplayName("a lookback under one would give a date no covering cycle at all")
  void aLookbackUnderOneIsRefused() {
    assertThatThrownBy(() -> DiscoveryCycle.at(at("2026-09-24T12:00:00Z"), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("covering");
  }
}
