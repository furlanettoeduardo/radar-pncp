package io.github.furlanettoeduardo.radar.domain;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.company.CompanyId;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfile;
import io.github.furlanettoeduardo.radar.domain.profile.SearchProfileId;
import io.github.furlanettoeduardo.radar.domain.profile.ValueRange;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Test data builder, so growing the SearchProfile record does not ripple through every test. */
public final class SearchProfileBuilder {

  private List<String> keywords = List.of();
  private Set<BrazilianState> states = Set.of();
  private Optional<ValueRange> valueRange = Optional.empty();

  private SearchProfileBuilder() {}

  public static SearchProfileBuilder aProfile() {
    return new SearchProfileBuilder();
  }

  public SearchProfileBuilder withKeywords(String... keywords) {
    this.keywords = List.of(keywords);
    return this;
  }

  public SearchProfileBuilder in(BrazilianState... states) {
    this.states = Set.of(states);
    return this;
  }

  public SearchProfileBuilder worthBetween(String minimum, String maximum) {
    this.valueRange = Optional.of(ValueRange.of(minimum, maximum));
    return this;
  }

  public SearchProfile build() {
    return new SearchProfile(
        new SearchProfileId(UUID.randomUUID()),
        new CompanyId(UUID.randomUUID()),
        keywords,
        states,
        valueRange);
  }
}
