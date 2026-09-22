package io.github.furlanettoeduardo.radar.ingestion.discovery;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The queues this service talks to, by name rather than by URL, so the region resolves them. */
@ConfigurationProperties(prefix = "radar.queues")
public record QueueProperties(String procurementDiscovered) {

  public QueueProperties {
    Objects.requireNonNull(
        procurementDiscovered, "radar.queues.procurement-discovered is required");
    if (procurementDiscovered.isBlank()) {
      throw new IllegalArgumentException("radar.queues.procurement-discovered must not be blank");
    }
  }
}
