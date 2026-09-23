package io.github.furlanettoeduardo.radar.ingestion;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling, and the lock that stops two instances running the same job.
 *
 * <p>The lock lives in PostgreSQL rather than in memory because "only one instance" has to stay
 * true across a deploy, when two instances briefly exist at once, and across a restart, when the
 * old instance's memory is gone but the work it started may not be.
 *
 * <p>{@code usingDbTime} asks the database for the current instant rather than trusting each
 * instance's clock. Two hosts whose clocks differ by a minute would otherwise disagree about when a
 * lock expires, which is the one thing a lock must not be vague about.
 *
 * <p>Used through {@link LockingTaskExecutor} rather than the {@code @SchedulerLock} annotation, so
 * that {@code lockAtMostFor} can be derived from the run deadline in code instead of repeated as a
 * string that somebody has to remember to keep larger.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
  }

  @Bean
  LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }
}
