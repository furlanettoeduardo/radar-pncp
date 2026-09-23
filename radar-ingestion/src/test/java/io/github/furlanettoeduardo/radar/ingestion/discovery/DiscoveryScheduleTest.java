package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The two things about a schedule that are wrong in ways tests normally miss.
 *
 * <p>A lock shorter than the run it protects lets a second instance start while the first is still
 * working, and it does so only on the slow runs, which are the ones where it matters most and where
 * nobody is watching. A cron without an explicit zone runs at the wrong time of day on a host in
 * UTC and looks perfectly correct on a laptop in Brasilia.
 */
@SpringBootTest(
    classes = DiscoveryScheduleTest.BindTheRealConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DiscoveryScheduleTest {

  @Configuration
  @EnableConfigurationProperties(PncpProperties.class)
  static class BindTheRealConfiguration {}

  private final PncpProperties pncp;

  DiscoveryScheduleTest(@Autowired PncpProperties pncp) {
    this.pncp = pncp;
  }

  @Test
  @DisplayName("the lock always outlives the run deadline it protects")
  void theLockOutlivesTheRun() {
    DiscoverySchedule schedule = new DiscoverySchedule(null, null, pncp, null);

    assertThat(schedule.lockAtMostFor())
        .as("a lock shorter than the deadline lets a second instance join a slow run")
        .isGreaterThan(pncp.operationDeadline());
  }

  @Test
  @DisplayName("the lock is derived from the deadline, so the two cannot drift apart")
  void theLockIsDerivedFromTheDeadline() {
    PncpProperties slower = withDeadline(Duration.ofMinutes(20));

    assertThat(new DiscoverySchedule(null, null, slower, null).lockAtMostFor())
        .as("raising the deadline must raise the lock without anybody remembering to")
        .isGreaterThan(Duration.ofMinutes(20));
  }

  @Test
  @DisplayName("the cron names its zone, and it is the zone PNCP dates are written in")
  void theCronNamesBrasilia() throws Exception {
    Method run = DiscoverySchedule.class.getMethod("runDiscovery");
    Scheduled scheduled = run.getAnnotation(Scheduled.class);

    assertThat(scheduled.zone())
        .as("on a host in UTC an unzoned cron puts the first run of the day at 21:17 yesterday")
        .isEqualTo("America/Sao_Paulo");
    assertThat(scheduled.cron())
        .as("every three hours, off the hour, first attempt just after midnight")
        .isEqualTo("0 17 */3 * * *");
  }

  private PncpProperties withDeadline(Duration deadline) {
    return new PncpProperties(
        pncp.baseUrl(),
        pncp.modalityCodes(),
        pncp.pageSize(),
        pncp.maxPagesPerChunk(),
        pncp.maxConcurrentRequests(),
        pncp.connectTimeout(),
        pncp.readTimeout(),
        deadline,
        pncp.userAgent());
  }
}
