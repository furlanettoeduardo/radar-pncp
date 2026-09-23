package io.github.furlanettoeduardo.radar.ingestion.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.Message;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

/**
 * The publisher against a real SQS implementation.
 *
 * <p>LocalStack rather than a mock, because the thing worth testing is the part a mock would have
 * to assume: that the record serialises to something SQS accepts and deserialises back to an equal
 * record. The same code path runs against real SQS in production — only the endpoint differs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SqsProcurementPublisherIT {

  private static final String QUEUE = "radar-procurement-discovered";

  // This service needs a database to start at all, since its SQS consumer writes procurements.
  // Nothing here touches it; it is here so the context can come up the way production does.
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
          .withServices(LocalStackContainer.Service.SQS);

  @DynamicPropertySource
  static void pointSpringAtLocalStack(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
    registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
    registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
    registry.add(
        "spring.cloud.aws.sqs.endpoint",
        () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
    registry.add("radar.queues.procurement-discovered", () -> QUEUE);
    // This test reads the queue itself; a live listener would race it for the messages.
    registry.add("radar.consumer.enabled", () -> "false");
  }

  private final ProcurementPublisher publisher;
  private final SqsTemplate sqs;
  private final SqsAsyncClient client;

  SqsProcurementPublisherIT(
      @Autowired ProcurementPublisher publisher,
      @Autowired SqsTemplate sqs,
      @Autowired SqsAsyncClient client) {
    this.publisher = publisher;
    this.sqs = sqs;
    this.client = client;
  }

  @BeforeEach
  void createTheQueue() {
    client.createQueue(CreateQueueRequest.builder().queueName(QUEUE).build()).join();
  }

  @Test
  @DisplayName("a published procurement arrives on the queue and deserialises back unchanged")
  void aPublishedProcurementRoundTrips() {
    ProcurementDiscovered sent =
        ProcurementDiscovered.of(
            "44935278000126-1-000343/2025",
            "0f5d1a5b1c2f4e6a8b9c0d1e2f3a4b5c",
            "2026-09-01T17:22:01Z",
            "{\"numeroControlePNCP\":\"44935278000126-1-000343/2025\"}");

    publisher.publish(sent);

    Optional<Message<ProcurementDiscovered>> received =
        sqs.receive(
            from -> from.queue(QUEUE).pollTimeout(Duration.ofSeconds(10)),
            ProcurementDiscovered.class);

    assertThat(received).isPresent();
    assertThat(received.get().getPayload()).isEqualTo(sent);
  }

  @Test
  @DisplayName("an undated procurement survives the round trip with its absent timestamp intact")
  void anUndatedProcurementRoundTrips() {
    ProcurementDiscovered sent =
        ProcurementDiscovered.of("undated-1/2026", "hash-undated", null, "{\"a\":1}");

    publisher.publish(sent);

    Optional<Message<ProcurementDiscovered>> received =
        sqs.receive(
            from -> from.queue(QUEUE).pollTimeout(Duration.ofSeconds(10)),
            ProcurementDiscovered.class);

    assertThat(received).isPresent();
    assertThat(received.get().getPayload().sourceUpdatedAt()).isNull();
    assertThat(received.get().getPayload()).isEqualTo(sent);
  }

  @Test
  @DisplayName("the schema version travels with the message, so a consumer never has to infer it")
  void theSchemaVersionTravels() {
    publisher.publish(
        ProcurementDiscovered.of(
            "versioned-1/2026", "hash-v", "2026-09-01T17:22:01Z", "{\"a\":1}"));

    Optional<Message<ProcurementDiscovered>> received =
        sqs.receive(
            from -> from.queue(QUEUE).pollTimeout(Duration.ofSeconds(10)),
            ProcurementDiscovered.class);

    assertThat(received).isPresent();
    assertThat(received.get().getPayload().schemaVersion())
        .isEqualTo(ProcurementDiscovered.CURRENT_SCHEMA_VERSION);
  }
}
