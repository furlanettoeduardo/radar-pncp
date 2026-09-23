package io.github.furlanettoeduardo.radar.ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.ingestion.discovery.ProcurementPublisher;
import io.github.furlanettoeduardo.radar.ingestion.persistence.SchemaFixture;
import io.github.furlanettoeduardo.radar.ingestion.pncp.PncpJson;
import io.github.furlanettoeduardo.radar.ingestion.pncp.SampleFixtures;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * A database outage must not send good messages to the dead letter queue.
 *
 * <p>This is the failure the redrive policy cannot see. It counts receives, and a Postgres
 * maintenance reboot consumes them exactly as a malformed payload does. At three receives and a
 * plain visibility timeout, an outage lasting longer than three timeouts dead-letters every message
 * in flight. Redrive would recover them, because the consumer is idempotent, but a routine
 * maintenance window should not need an operator at all.
 *
 * <p><b>The outage here is longer than three visibility timeouts on purpose.</b> With a base of 2s,
 * an unmodified consumer would take its three receives at roughly 0s, 2s and 4s and dead-letter the
 * message by 6s. The backoff spreads the same three receives across 2s, 8s and 32s, so the third
 * one lands after the outage has passed. A test whose outage ended before the third receive would
 * pass either way and prove nothing.
 *
 * <p>What is real here and what is not: SQS is real (LocalStack), the redrive policy is real, the
 * queue attributes are the configured ones, and PostgreSQL is real. The outage itself is injected
 * at the {@link DataSource}, which is where a genuinely unreachable database surfaces — Spring
 * raises {@code CannotGetJdbcConnectionException} from exactly this point. Stopping the container
 * would test Docker's port allocation rather than the consumer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(DatabaseOutageDoesNotPoisonMessagesIT.OutageInjector.class)
class DatabaseOutageDoesNotPoisonMessagesIT {

  private static final String QUEUE = "radar-procurement-discovered";
  private static final String DEAD_LETTER_QUEUE = "radar-procurement-discovered-dlq";
  private static final String CONTROL_NUMBER = "44935278000126-1-000343/2025";

  /** Small multiples of the production shape, so the relationships hold and the test is quick. */
  private static final Duration BASE_VISIBILITY = Duration.ofSeconds(2);

  private static final AtomicBoolean DATABASE_IS_DOWN = new AtomicBoolean();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
          .withServices(LocalStackContainer.Service.SQS);

  @DynamicPropertySource
  static void prepare(DynamicPropertyRegistry registry) {
    SchemaFixture.migrated(POSTGRES);
    createQueuesWithRedrivePolicy();

    registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
    registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
    registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
    registry.add(
        "spring.cloud.aws.sqs.endpoint",
        () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
    registry.add("radar.queues.procurement-discovered", () -> QUEUE);
    registry.add("radar.consumer.visibility-timeout", () -> BASE_VISIBILITY.toSeconds() + "s");
  }

  private static void createQueuesWithRedrivePolicy() {
    try (SqsAsyncClient sqs = localStackClient()) {
      String dlqUrl =
          sqs.createQueue(CreateQueueRequest.builder().queueName(DEAD_LETTER_QUEUE).build())
              .join()
              .queueUrl();
      String dlqArn =
          sqs.getQueueAttributes(
                  GetQueueAttributesRequest.builder()
                      .queueUrl(dlqUrl)
                      .attributeNames(QueueAttributeName.QUEUE_ARN)
                      .build())
              .join()
              .attributes()
              .get(QueueAttributeName.QUEUE_ARN);

      sqs.createQueue(
              CreateQueueRequest.builder()
                  .queueName(QUEUE)
                  .attributes(
                      Map.of(
                          QueueAttributeName.VISIBILITY_TIMEOUT,
                          String.valueOf(BASE_VISIBILITY.toSeconds()),
                          QueueAttributeName.REDRIVE_POLICY,
                          "{\"maxReceiveCount\":\"3\",\"deadLetterTargetArn\":\"" + dlqArn + "\"}"))
                  .build())
          .join();
    }
  }

  private static SqsAsyncClient localStackClient() {
    return SqsAsyncClient.builder()
        .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
        .region(software.amazon.awssdk.regions.Region.of(LOCALSTACK.getRegion()))
        .credentialsProvider(
            software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                    LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
        .build();
  }

  private final ProcurementPublisher publisher;
  private final ProcurementRepository procurements;
  private final SqsAsyncClient sqs;

  DatabaseOutageDoesNotPoisonMessagesIT(
      @Autowired ProcurementPublisher publisher,
      @Autowired ProcurementRepository procurements,
      @Autowired SqsAsyncClient sqs) {
    this.publisher = publisher;
    this.procurements = procurements;
    this.sqs = sqs;
  }

  @Test
  @DisplayName("a message survives an outage longer than three visibility timeouts")
  void aMessageSurvivesAnOutageLongerThanThreeVisibilityTimeouts() throws Exception {
    DATABASE_IS_DOWN.set(true);
    publisher.publish(aDiscoveredProcurement());

    // Longer than 3 x 2s, which is where an unmodified consumer would have dead-lettered it.
    Thread.sleep(Duration.ofSeconds(9).toMillis());
    DATABASE_IS_DOWN.set(false);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(procurements.findByControlNumber(new PncpControlNumber(CONTROL_NUMBER)))
                    .as("the message waited out the outage and was stored on a later receive")
                    .isPresent());

    assertThat(messagesIn(DEAD_LETTER_QUEUE))
        .as("nothing was wrong with this message, so nothing should have dead-lettered it")
        .isZero();
  }

  private int messagesIn(String queueName) {
    String url = sqs.getQueueUrl(builder -> builder.queueName(queueName)).join().queueUrl();
    String count =
        sqs.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(url)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                    .build())
            .join()
            .attributes()
            .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
    return Integer.parseInt(count);
  }

  private static ProcurementDiscovered aDiscoveredProcurement() {
    try {
      String payload =
          PncpJson.mapper()
              .readTree(SampleFixtures.read("contratacoes-proposta.json"))
              .get("data")
              .get(0)
              .toString();
      return ProcurementDiscovered.of(
          CONTROL_NUMBER, "hash-of-the-payload", "2026-09-01T17:22:01Z", payload);
    } catch (Exception cause) {
      throw new IllegalStateException("could not read the recorded sample", cause);
    }
  }

  /**
   * Makes the database unreachable on demand, exactly where an unreachable one announces itself.
   */
  @TestConfiguration
  static class OutageInjector {

    @Bean
    @Primary
    DataSource outageProneDataSource() {
      DriverManagerDataSource real = new DriverManagerDataSource();
      real.setUrl(POSTGRES.getJdbcUrl());
      real.setUsername(POSTGRES.getUsername());
      real.setPassword(POSTGRES.getPassword());

      return new DelegatingDataSource(real) {
        @Override
        public Connection getConnection() throws java.sql.SQLException {
          refuseIfDown();
          return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password)
            throws java.sql.SQLException {
          refuseIfDown();
          return super.getConnection(username, password);
        }

        private void refuseIfDown() {
          if (DATABASE_IS_DOWN.get()) {
            throw new CannotGetJdbcConnectionException("connection refused: the database is down");
          }
        }
      };
    }
  }
}
