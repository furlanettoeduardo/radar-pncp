package io.github.furlanettoeduardo.radar.ingestion.consumer;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.procurement.ProcurementIngestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain's ingestion rule.
 *
 * <p>An explicit bean because {@link ProcurementIngestion} is a domain class and the domain carries
 * no Spring annotations. That constraint is enforced by the build, and this is what it costs: three
 * lines here instead of one annotation there, in exchange for a domain that can be understood and
 * tested without knowing Spring exists.
 */
@Configuration(proxyBeanMethods = false)
public class ConsumerConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ConsumerConfiguration.class);

  @Bean
  ProcurementIngestion procurementIngestion(ProcurementRepository procurements) {
    return new ProcurementIngestion(procurements);
  }

  /**
   * Says so, loudly, when this instance will never consume anything.
   *
   * <p>A consumer switched off in production is a failure no test can catch: everything starts,
   * every health check passes, discovery keeps publishing, and the queue simply fills until its
   * retention period quietly discards the oldest messages. The only symptom is an absence. So the
   * absence announces itself at startup rather than being inferred from a graph a week later.
   */
  @Bean
  @ConditionalOnProperty(prefix = "radar.consumer", name = "enabled", havingValue = "false")
  ApplicationRunner warnThatNothingIsConsuming() {
    return args ->
        LOG.warn(
            "radar.consumer.enabled=false: this instance publishes but never consumes. Messages "
                + "will accumulate on the queue and be discarded when they reach their retention "
                + "period. This is only correct if another instance is consuming.");
  }
}
