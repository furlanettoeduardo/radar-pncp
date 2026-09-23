package io.github.furlanettoeduardo.radar.ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Telling a broken message apart from a broken afternoon.
 *
 * <p>A redrive policy counts receives, and it cannot see why a receive failed. Left alone, a
 * Postgres maintenance reboot consumes three receives exactly like a malformed payload does, and
 * sends perfectly good messages to the dead letter queue. Redrive would recover them, because the
 * consumer is idempotent — but a routine maintenance window should not need an operator.
 *
 * <p>So the two get different treatment, and the classification is tested on its own because the
 * obvious implementation of it is wrong. See {@link
 * #aLostConnectionIsNotASpringTransientException}.
 */
class ConsumerFailuresTest {

  @Test
  @DisplayName("a lost database connection is transient, and Spring does not call it that")
  void aLostConnectionIsNotASpringTransientException() {
    CannotGetJdbcConnectionException databaseDown =
        new CannotGetJdbcConnectionException("connection refused", new SQLException());

    assertThat(databaseDown)
        .as("the trap: classifying on Spring's own Transient type misses the case that matters")
        .isNotInstanceOf(TransientDataAccessException.class);
    assertThat(ConsumerFailures.classify(databaseDown)).isEqualTo(FailureKind.TRANSIENT);
  }

  @Test
  @DisplayName("a query timeout is transient too")
  void aQueryTimeoutIsTransient() {
    assertThat(ConsumerFailures.classify(new QueryTimeoutException("took too long")))
        .isEqualTo(FailureKind.TRANSIENT);
  }

  @Test
  @DisplayName("a constraint violation is poison: the payload is wrong and will stay wrong")
  void aConstraintViolationIsPoison() {
    assertThat(ConsumerFailures.classify(new DataIntegrityViolationException("check failed")))
        .as("this is the one database failure that retrying cannot fix")
        .isEqualTo(FailureKind.POISON);
  }

  @Test
  @DisplayName("a contract violation is poison, and so is anything unrecognised")
  void contractViolationsAndUnknownsArePoison() {
    assertThat(ConsumerFailures.classify(new UnsupportedSchemaVersionException(2, 1)))
        .isEqualTo(FailureKind.POISON);
    assertThat(ConsumerFailures.classify(new IllegalStateException("a bug we have not met")))
        .as("an unknown failure should reach the dead letter queue fast, where it is visible")
        .isEqualTo(FailureKind.POISON);
  }

  @Test
  @DisplayName("a transient failure wrapped in something else is still transient")
  void aWrappedTransientFailureIsStillTransient() {
    RuntimeException wrapped =
        new IllegalStateException(
            "while consuming", new CannotGetJdbcConnectionException("connection refused"));

    assertThat(ConsumerFailures.classify(wrapped)).isEqualTo(FailureKind.TRANSIENT);
  }

  @Test
  @DisplayName("the backoff spans an outage rather than three visibility timeouts")
  void theBackoffSpansAnOutage() {
    Duration base = Duration.ofSeconds(30);

    assertThat(ConsumerFailures.backoffSeconds(1, base)).isEqualTo(30);
    assertThat(ConsumerFailures.backoffSeconds(2, base)).isEqualTo(120);
    assertThat(ConsumerFailures.backoffSeconds(3, base)).isEqualTo(480);
  }

  @Test
  @DisplayName("three receives with that backoff cover about ten minutes, not ninety seconds")
  void threeReceivesCoverTenMinutes() {
    Duration base = Duration.ofSeconds(30);
    int total =
        ConsumerFailures.backoffSeconds(1, base)
            + ConsumerFailures.backoffSeconds(2, base)
            + ConsumerFailures.backoffSeconds(3, base);

    assertThat(total)
        .as("an RDS single-AZ maintenance reboot is minutes, not seconds")
        .isEqualTo(630);
    assertThat(total)
        .as("without the backoff the same three receives span 3 x 30s")
        .isGreaterThan(7 * (int) base.toSeconds());
  }

  @Test
  @DisplayName("the backoff never exceeds what SQS will accept")
  void theBackoffIsCappedAtTheSqsMaximum() {
    assertThat(ConsumerFailures.backoffSeconds(10, Duration.ofSeconds(30)))
        .as("SQS refuses a visibility timeout over 12 hours, and a refusal here loses the backoff")
        .isEqualTo(43_200);
  }
}
