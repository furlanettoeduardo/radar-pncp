package io.github.furlanettoeduardo.radar.ingestion.consumer;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.procurement.ProcurementIngestion;
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

  @Bean
  ProcurementIngestion procurementIngestion(ProcurementRepository procurements) {
    return new ProcurementIngestion(procurements);
  }
}
