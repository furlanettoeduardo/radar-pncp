package io.github.furlanettoeduardo.radar.ingestion.discovery;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.github.furlanettoeduardo.radar.shared.ProcurementDiscovered;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Publishes a discovered procurement to SQS.
 *
 * <p>One message per procurement rather than a batch. At the measured volumes — roughly 1,500
 * notices a day nationally — per-message publishing uses about 22 percent of the one million
 * request monthly free tier, and receives dominate that total anyway: an idle consumer long polling
 * at the twenty second maximum spends 129,600 requests a month whether or not anything arrives.
 * Batching would save requests we are not short of, in exchange for per-entry partial failure
 * handling, which is the kind of code that is wrong for a year before anyone notices. The threshold
 * at which that changes is recorded in {@code docs/configuration.md}.
 *
 * <p>The endpoint is configuration, not code, so this same class talks to LocalStack in tests and
 * to real SQS in production with no branching.
 */
@Component
public final class SqsProcurementPublisher implements ProcurementPublisher {

  private final SqsTemplate sqs;
  private final QueueProperties queues;

  public SqsProcurementPublisher(SqsTemplate sqs, QueueProperties queues) {
    this.sqs = Objects.requireNonNull(sqs, "a publisher needs an SQS template");
    this.queues = Objects.requireNonNull(queues, "a publisher needs to know its queue");
  }

  @Override
  public void publish(ProcurementDiscovered message) {
    Objects.requireNonNull(message, "there is nothing to publish");
    sqs.send(to -> to.queue(queues.procurementDiscovered()).payload(message));
  }
}
