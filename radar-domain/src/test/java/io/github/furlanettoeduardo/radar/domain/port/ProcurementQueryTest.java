package io.github.furlanettoeduardo.radar.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcurementQueryTest {

  private static final LocalDate FROM = LocalDate.of(2026, 9, 15);
  private static final LocalDate TO = LocalDate.of(2026, 9, 21);

  @Test
  @DisplayName("an empty set of states means every state, not no states")
  void anEmptyStateSetMeansEverywhere() {
    ProcurementQuery query = new ProcurementQuery(FROM, TO, Set.of());

    assertThat(query.coversEveryState()).isTrue();
    assertThat(new ProcurementQuery(FROM, TO, Set.of(BrazilianState.SP)).coversEveryState())
        .isFalse();
  }

  @Test
  @DisplayName("rejects a range that ends before it starts")
  void rejectsAnInvertedRange() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ProcurementQuery(TO, FROM, Set.of()));
  }
}
