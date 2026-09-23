package io.github.furlanettoeduardo.radar.ingestion.persistence;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.ingestion.discovery.DiscoveryChunkRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the persistence adapters explicitly.
 *
 * <p>Explicit beans rather than {@code @Repository} component scanning, because that annotation
 * makes Spring proxy the bean for persistence exception translation and the adapters are final
 * classes. The translation is redundant here in any case: {@link JdbcClient} already raises
 * Spring's {@code DataAccessException} hierarchy rather than raw {@code SQLException}.
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

  @Bean
  ProcurementRepository procurementRepository(JdbcClient jdbcClient) {
    return new JdbcProcurementRepository(jdbcClient);
  }

  @Bean
  DiscoveryChunkRepository discoveryChunkRepository(JdbcClient jdbcClient) {
    return new JdbcDiscoveryChunkRepository(jdbcClient);
  }
}
