package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single place where a naive PNCP timestamp becomes an Instant. ADR 0005 pins this conversion
 * because the proposal deadline rule disqualifies: drift here does not skew a score, it deletes
 * matches with no error anywhere.
 */
class PncpTimestampsTest {

  @Test
  @DisplayName("reads a naive PNCP timestamp as Sao Paulo local time")
  void readsNaiveTimestampsAsSaoPauloTime() {
    assertThat(PncpTimestamps.toInstant("2026-09-28T09:00:00"))
        .isEqualTo(Instant.parse("2026-09-28T12:00:00Z"));
  }

  @Test
  @DisplayName("converts every timestamp shape the recorded samples contain")
  void convertsTheSampleTimestamps() {
    assertThat(PncpTimestamps.toInstant("2026-09-01T14:20:41"))
        .isEqualTo(Instant.parse("2026-09-01T17:20:41Z"));
    assertThat(PncpTimestamps.toInstant("2026-09-15T04:00:06"))
        .isEqualTo(Instant.parse("2026-09-15T07:00:06Z"));
  }

  /**
   * Brazil abolished daylight saving in 2019, so every date this system will ever query sits at a
   * flat minus three. A hardcoded ZoneOffset.of("-03:00") would therefore pass every other test in
   * this class and stay wrong forever. This one uses a date from when Brazil still observed DST, so
   * it passes only if the conversion consults the zone rules.
   */
  @Test
  @DisplayName("uses zone rules, not a fixed offset: a 2018 summer date converts at minus two")
  void usesZoneRulesRatherThanAFixedOffset() {
    assertThat(PncpTimestamps.toInstant("2018-11-20T12:00:00"))
        .isEqualTo(Instant.parse("2018-11-20T14:00:00Z"));
    assertThat(PncpTimestamps.toInstant("2018-10-20T12:00:00"))
        .isEqualTo(Instant.parse("2018-10-20T15:00:00Z"));
  }

  @Test
  @DisplayName("refuses a blank or malformed timestamp rather than guessing one")
  void refusesWhatItCannotRead() {
    assertThatIllegalArgumentException().isThrownBy(() -> PncpTimestamps.toInstant(null));
    assertThatIllegalArgumentException().isThrownBy(() -> PncpTimestamps.toInstant("  "));
    assertThatIllegalArgumentException().isThrownBy(() -> PncpTimestamps.toInstant("28/09/2026"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PncpTimestamps.toInstant("2026-09-28T09:00:00Z"));
  }
}
