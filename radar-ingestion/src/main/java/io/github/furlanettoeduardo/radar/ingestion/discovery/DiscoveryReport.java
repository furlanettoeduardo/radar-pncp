package io.github.furlanettoeduardo.radar.ingestion.discovery;

/**
 * What one discovery run did, for the log line and the metric.
 *
 * <p>The two numbers are separate because they can differ: a procurement can be discovered and fail
 * to publish, and a run that discovered 400 and published 12 is a very different morning from one
 * that discovered 12.
 */
public record DiscoveryReport(int discovered, int published) {

  public DiscoveryReport {
    if (discovered < 0 || published < 0) {
      throw new IllegalArgumentException("a discovery report cannot count backwards");
    }
    if (published > discovered) {
      throw new IllegalArgumentException(
          "published %d of %d discovered, which is more than were found"
              .formatted(published, discovered));
    }
  }
}
