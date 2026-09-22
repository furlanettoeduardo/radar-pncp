package io.github.furlanettoeduardo.radar.domain.company;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompanyTest {

  @Test
  @DisplayName("carries the identity, registration, trade and location of a supplier")
  void carriesTheSupplierIdentity() {
    Company company =
        new Company(
            new CompanyId(UUID.randomUUID()),
            Cnpj.of("44.935.278/0001-26"),
            Cnae.of("6201-5/01"),
            BrazilianState.SP);

    assertThat(company.cnpj().digits()).isEqualTo("44935278000126");
    assertThat(company.primaryCnae().division()).isEqualTo("62");
    assertThat(company.state()).isEqualTo(BrazilianState.SP);
  }
}
