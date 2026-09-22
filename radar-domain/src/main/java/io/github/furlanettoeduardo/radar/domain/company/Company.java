package io.github.furlanettoeduardo.radar.domain.company;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.util.Objects;

/**
 * A supplier looking for public business.
 *
 * <p>Its search profiles reference it by identity rather than living inside it, so scoring a
 * profile never needs to load the company behind it.
 */
public record Company(CompanyId id, Cnpj cnpj, Cnae primaryCnae, BrazilianState state) {

  public Company {
    Objects.requireNonNull(id, "a company must have an id");
    Objects.requireNonNull(cnpj, "a company must have a CNPJ");
    Objects.requireNonNull(primaryCnae, "a company must have a primary CNAE");
    Objects.requireNonNull(state, "a company must have a state");
  }
}
