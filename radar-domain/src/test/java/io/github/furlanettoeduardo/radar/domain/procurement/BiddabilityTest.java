package io.github.furlanettoeduardo.radar.domain.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a notice is something a supplier could bid on at all.
 *
 * <p>This answers exactly one question: does a proposal window exist. Whether that window is still
 * open is a scoring concern and lives in the deadline rule, which grades urgency and disqualifies
 * what has closed. Folding the two together would turn "we saw this too late" into "this was never
 * an opportunity", and those need different answers.
 *
 * <p>The distinction that matters here is between a business outcome and bad data. A dispensa with
 * no window is exactly what a dispensa is — 9 of the 10 recorded modality 8 notices look like that
 * — and it is not an error. Half a window is neither: it is inconsistent data, and treating it as a
 * business outcome would hide a contract change behind a routine counter.
 */
class BiddabilityTest {

  private static final Instant OPENS = Instant.parse("2026-09-02T11:00:00Z");
  private static final Instant CLOSES = Instant.parse("2026-09-21T20:30:00Z");

  @Test
  @DisplayName("a notice with both dates is biddable")
  void bothDatesPresentIsBiddable() {
    assertThat(Biddability.assess(Optional.of(OPENS), Optional.of(CLOSES)))
        .isInstanceOf(BiddabilityAssessment.Biddable.class);
  }

  @Test
  @DisplayName("a window that opens and closes at the same instant is still a window")
  void anInstantaneousWindowIsBiddable() {
    assertThat(Biddability.assess(Optional.of(OPENS), Optional.of(OPENS)))
        .isInstanceOf(BiddabilityAssessment.Biddable.class);
  }

  @Test
  @DisplayName("no proposal window at all is a business outcome, not an error: it cannot be bid on")
  void neitherDatePresentIsNotBiddable() {
    assertThat(Biddability.assess(Optional.empty(), Optional.empty()))
        .isInstanceOfSatisfying(
            BiddabilityAssessment.NotBiddable.class,
            notBiddable -> assertThat(notBiddable.reason()).contains("no proposal window"));
  }

  @Test
  @DisplayName("an opening date without a closing date is malformed, not merely unbiddable")
  void anOpeningWithoutAClosingIsMalformed() {
    assertThat(Biddability.assess(Optional.of(OPENS), Optional.empty()))
        .isInstanceOfSatisfying(
            BiddabilityAssessment.Malformed.class,
            malformed -> assertThat(malformed.reason()).contains("dataEncerramentoProposta"));
  }

  @Test
  @DisplayName("a closing date without an opening date is malformed too")
  void aClosingWithoutAnOpeningIsMalformed() {
    assertThat(Biddability.assess(Optional.empty(), Optional.of(CLOSES)))
        .isInstanceOfSatisfying(
            BiddabilityAssessment.Malformed.class,
            malformed -> assertThat(malformed.reason()).contains("dataAberturaProposta"));
  }

  @Test
  @DisplayName("a window that closes before it opens is malformed")
  void anInvertedWindowIsMalformed() {
    assertThat(Biddability.assess(Optional.of(CLOSES), Optional.of(OPENS)))
        .isInstanceOfSatisfying(
            BiddabilityAssessment.Malformed.class,
            malformed -> assertThat(malformed.reason()).contains("before it opens"));
  }

  @Test
  @DisplayName(
      "a window already closed is still biddable here: lateness is the deadline rule's job")
  void anAlreadyClosedWindowIsStillBiddable() {
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");

    assertThat(Biddability.assess(Optional.of(longAgo), Optional.of(longAgo.plusSeconds(3600))))
        .as("this class does not read the clock, and must not start")
        .isInstanceOf(BiddabilityAssessment.Biddable.class);
  }
}
