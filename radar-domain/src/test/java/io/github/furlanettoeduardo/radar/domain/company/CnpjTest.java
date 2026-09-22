package io.github.furlanettoeduardo.radar.domain.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CnpjTest {

  @Test
  @DisplayName("keeps only the digits, so the punctuation a user types does not matter")
  void normalisesToDigits() {
    assertThat(Cnpj.of("44.935.278/0001-26").digits()).isEqualTo("44935278000126");
    assertThat(Cnpj.of("44935278000126").digits()).isEqualTo("44935278000126");
  }

  @Test
  @DisplayName("two CNPJs written differently are the same CNPJ")
  void equalityIgnoresFormatting() {
    assertThat(Cnpj.of("44.935.278/0001-26")).isEqualTo(Cnpj.of("44935278000126"));
  }

  @Test
  @DisplayName("rejects anything that is not fourteen digits")
  void rejectsWrongLength() {
    assertThatIllegalArgumentException().isThrownBy(() -> Cnpj.of("4493527800012"));
    assertThatIllegalArgumentException().isThrownBy(() -> Cnpj.of("449352780001267"));
  }
}
