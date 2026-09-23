package io.github.furlanettoeduardo.radar.domain.procurement;

import java.io.Serial;

/**
 * Another writer kept winning the race, so this attempt gave up.
 *
 * <p>Thrown rather than swallowed on purpose. The caller is a queue consumer, and a message that is
 * not acknowledged is redelivered: giving up here hands the retry to SQS, which already has
 * backoff, a receive count and a dead letter queue. Looping here instead would reimplement all
 * three, badly, inside a listener thread.
 *
 * <p>Reaching it at all means several consumers are fighting over the same procurement, which at
 * the expected volumes should be rare enough to be worth investigating rather than tuning away.
 */
public class ProcurementContentionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ProcurementContentionException(PncpControlNumber controlNumber, int attempts) {
    super(
        "gave up storing %s after %d attempts: another writer won each time"
            .formatted(controlNumber.value(), attempts));
  }
}
