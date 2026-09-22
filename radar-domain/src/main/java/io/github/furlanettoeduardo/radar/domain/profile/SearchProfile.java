package io.github.furlanettoeduardo.radar.domain.profile;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.company.Cnae;
import io.github.furlanettoeduardo.radar.domain.company.CompanyId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a company is looking for. Owned by a company, referenced by id rather than embedded, so
 * scoring loads a profile without loading the company behind it.
 */
public record SearchProfile(
    SearchProfileId id,
    CompanyId companyId,
    List<String> keywords,
    List<Cnae> cnaes,
    Set<BrazilianState> states,
    Optional<ValueRange> valueRange) {

  public SearchProfile {
    Objects.requireNonNull(id, "a search profile must have an id");
    Objects.requireNonNull(companyId, "a search profile must belong to a company");
    Objects.requireNonNull(keywords, "keywords must not be null, use an empty list instead");
    keywords.forEach(
        keyword -> {
          if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("a keyword must not be blank");
          }
        });
    keywords = List.copyOf(keywords);
    Objects.requireNonNull(cnaes, "cnaes must not be null, use an empty list instead");
    cnaes = List.copyOf(cnaes);
    Objects.requireNonNull(states, "states must not be null, use an empty set instead");
    states = Set.copyOf(states);
    Objects.requireNonNull(valueRange, "value range must not be null, use Optional.empty()");
  }
}
