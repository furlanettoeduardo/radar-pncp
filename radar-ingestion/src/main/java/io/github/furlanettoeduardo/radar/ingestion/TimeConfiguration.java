package io.github.furlanettoeduardo.radar.ingestion;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock, as a bean, so that nothing in this service calls {@code Instant.now()} directly.
 *
 * <p>Discovery decides which Brazilian calendar dates it owes, and whether a fetch happened after a
 * date closed. Both are wrong for three hours every evening if the reading is taken in the wrong
 * zone or at an uncontrolled moment, and neither is testable at that boundary unless the clock
 * arrives from outside.
 *
 * <p>UTC rather than {@code systemDefaultZone}: only {@link Clock#instant()} is ever read, and a
 * clock whose zone depends on the host is an invitation to read something else from it later.
 * Conversion to {@code America/Sao_Paulo} happens in one place, in {@code DiscoveryCycle}.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
