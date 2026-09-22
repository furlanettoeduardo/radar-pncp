package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What one discovery run looks for.
 *
 * <p>{@code lookbackDays} overlaps deliberately with the schedule rather than matching it. A run
 * that looks back exactly as far as the interval loses everything published during an outage.
 *
 * <p>The overlap is free on the <em>storage</em> side, because the consumer deduplicates on content
 * hash, and it is <em>not</em> free on the request side: the redundant day is a full day of pages
 * fetched from a public API on every run, forever. At the measured volumes that is roughly 100 to
 * 160 extra page requests per run, which is 100 percent overhead on the minimum. The arithmetic,
 * and why it is still worth paying, is in {@code docs/configuration.md}.
 *
 * <p>An empty {@code states} means every state, which is what {@code ProcurementQuery} already
 * expresses and what PNCP accepts as an omitted {@code uf}.
 */
@ConfigurationProperties(prefix = "radar.discovery")
public record DiscoveryProperties(int lookbackDays, Set<BrazilianState> states) {

  public DiscoveryProperties {
    Objects.requireNonNull(states, "states must not be null, use an empty set to mean everywhere");
    if (lookbackDays < 1) {
      throw new IllegalArgumentException(
          "radar.discovery.lookback-days must be at least 1 but was " + lookbackDays);
    }
    states = Set.copyOf(states);
  }
}
