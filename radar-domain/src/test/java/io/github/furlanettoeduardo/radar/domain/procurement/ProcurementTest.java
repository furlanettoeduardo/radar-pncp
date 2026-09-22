package io.github.furlanettoeduardo.radar.domain.procurement;

import static io.github.furlanettoeduardo.radar.domain.ProcurementBuilder.aProcurement;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcurementTest {

  private static final Instant OPENS = Instant.parse("2026-09-22T12:00:00Z");

  @Test
  @DisplayName("rejects a proposal window that closes before it opens")
  void rejectsAnInvertedProposalWindow() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                aProcurement().openingAt(OPENS).closingAt(OPENS.minus(Duration.ofDays(1))).build());
  }

  @Test
  @DisplayName("accepts a window that opens and closes at the same instant")
  void acceptsAnInstantaneousWindow() {
    aProcurement().openingAt(OPENS).closingAt(OPENS).build();
  }
}
