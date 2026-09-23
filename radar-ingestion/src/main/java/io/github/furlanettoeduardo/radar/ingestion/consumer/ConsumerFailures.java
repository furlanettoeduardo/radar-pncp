package io.github.furlanettoeduardo.radar.ingestion.consumer;

import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Deciding whether a failed message deserves patience, and how much.
 *
 * <p>The classification looks obvious and is not. Spring has a {@code TransientDataAccessException}
 * hierarchy that reads as though it were exactly this question, and <b>it is the wrong one</b>: a
 * lost connection surfaces as {@code CannotGetJdbcConnectionException}, which extends {@code
 * NonTransientDataAccessResourceException}. Classifying on Spring's own notion of transient would
 * miss the single case this exists for. So the rule is stated the other way round: anything the
 * data access layer raises is the database's problem and worth waiting out, except the one kind
 * that is the payload's fault.
 *
 * <p>Anything unrecognised is poison on purpose. An unknown failure reaching the dead letter queue
 * in ninety seconds is a bug somebody notices; the same failure quietly retried for ten minutes is
 * a bug nobody notices.
 */
public final class ConsumerFailures {

  /** SQS refuses anything longer, and a refused call would lose the backoff entirely. */
  static final int MAX_SQS_VISIBILITY_SECONDS = 43_200;

  /**
   * Why four: three receives at the plain visibility timeout span 90 seconds, and an RDS single-AZ
   * maintenance reboot is minutes. A multiplier of two would span three and a half minutes, still
   * short of it. Four reaches about ten and a half minutes across the three receives, which covers
   * a routine reboot without an operator.
   */
  private static final int MULTIPLIER = 4;

  private ConsumerFailures() {}

  public static FailureKind classify(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataIntegrityViolationException) {
        // The one database failure the payload caused: a constraint it will violate every time.
        return FailureKind.POISON;
      }
      if (cause instanceof DataAccessException) {
        return FailureKind.TRANSIENT;
      }
      if (cause == cause.getCause()) {
        break;
      }
    }
    return FailureKind.POISON;
  }

  /**
   * How long to hide the message before the next receive, growing with each one.
   *
   * <p>The first wait is the queue's own visibility timeout, so the first retry happens exactly
   * when SQS would have redelivered anyway and nothing is slower in the ordinary case.
   */
  public static int backoffSeconds(int receiveCount, Duration baseVisibility) {
    long seconds = baseVisibility.toSeconds();
    for (int receive = 1; receive < receiveCount; receive++) {
      seconds *= MULTIPLIER;
      if (seconds >= MAX_SQS_VISIBILITY_SECONDS) {
        return MAX_SQS_VISIBILITY_SECONDS;
      }
    }
    return (int) Math.min(seconds, MAX_SQS_VISIBILITY_SECONDS);
  }
}
