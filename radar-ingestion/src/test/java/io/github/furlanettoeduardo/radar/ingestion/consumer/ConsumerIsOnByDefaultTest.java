package io.github.furlanettoeduardo.radar.ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The consumer is on unless somebody says otherwise.
 *
 * <p>Asserted rather than assumed because the failure it guards is invisible. A consumer switched
 * off in production starts cleanly, passes every health check, and shows no error anywhere: the
 * queue just fills until its retention period discards the oldest messages. Nothing else in this
 * suite would notice the default being flipped, since every test that cares sets the value
 * explicitly.
 */
class ConsumerIsOnByDefaultTest {

  @Test
  @DisplayName("omitting radar.consumer.enabled leaves the listener running")
  void theListenerRunsWhenNobodySaysOtherwise() {
    ConditionalOnProperty condition =
        ProcurementDiscoveredListener.class.getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("radar.consumer");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.matchIfMissing())
        .as("a missing property must mean on; off by default is a silent outage")
        .isTrue();
  }
}
