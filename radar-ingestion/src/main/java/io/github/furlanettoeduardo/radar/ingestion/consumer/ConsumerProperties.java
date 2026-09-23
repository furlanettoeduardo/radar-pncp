package io.github.furlanettoeduardo.radar.ingestion.consumer;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The queue's own behaviour, as this consumer understands it.
 *
 * <p>These are SQS <em>queue attributes</em>, set on the queue itself, which stage 8's Terraform
 * will own. They are configured here as well because the consumer has to agree with them: the
 * backoff's first step is the visibility timeout, so a value here that disagrees with the queue
 * would make the first retry either pointlessly early or needlessly late. The integration tests
 * create their queue from these values, which is what keeps the two honest.
 */
@ConfigurationProperties(prefix = "radar.consumer")
public record ConsumerProperties(Duration visibilityTimeout, int maxReceiveCount) {

  public ConsumerProperties {
    Objects.requireNonNull(visibilityTimeout, "radar.consumer.visibility-timeout is required");
    if (visibilityTimeout.isNegative() || visibilityTimeout.isZero()) {
      throw new IllegalArgumentException("radar.consumer.visibility-timeout must be positive");
    }
    if (maxReceiveCount < 1) {
      throw new IllegalArgumentException(
          "radar.consumer.max-receive-count must be at least 1 but was " + maxReceiveCount);
    }
  }
}
