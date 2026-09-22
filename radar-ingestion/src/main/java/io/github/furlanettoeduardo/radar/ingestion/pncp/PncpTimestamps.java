package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * The one place a naive PNCP timestamp becomes an {@link Instant}.
 *
 * <p>PNCP publishes wall clock times with no offset: {@code dataEncerramentoProposta} arrives as
 * {@code 2026-09-28T09:00:00}. They are Brasilia local time, and they are read here and nowhere
 * else, so the domain only ever sees instants.
 *
 * <p>ADR 0005 pins this conversion for a reason worth repeating at the call site: the proposal
 * deadline rule <em>disqualifies</em>. An hour of drift does not skew a score, it turns an open
 * procurement into a closed one and deletes the match with no error anywhere.
 *
 * <p>The zone is consulted by its rules rather than assumed to be a fixed offset. Brazil abolished
 * daylight saving in 2019, so every date this system will realistically query sits at a flat minus
 * three, and a hardcoded {@code -03:00} would look correct forever. A test using a 2018 date, when
 * Brazil still observed DST, is what keeps that mistake out.
 *
 * <p>Were a zone transition ever to return, {@code atZone} resolves a gap by shifting forward and
 * an overlap by taking the earlier offset. Nothing in this system depends on that choice today.
 */
public final class PncpTimestamps {

  /** Brasilia time, the zone PNCP publishes in. */
  public static final ZoneId PNCP_ZONE = ZoneId.of("America/Sao_Paulo");

  private PncpTimestamps() {}

  public static Instant toInstant(String naiveTimestamp) {
    if (naiveTimestamp == null || naiveTimestamp.isBlank()) {
      throw new IllegalArgumentException("a PNCP timestamp must not be null or blank");
    }
    try {
      return LocalDateTime.parse(naiveTimestamp.trim()).atZone(PNCP_ZONE).toInstant();
    } catch (DateTimeParseException cause) {
      throw new IllegalArgumentException(
          "not a PNCP timestamp, expected a naive ISO local date time: " + naiveTimestamp, cause);
    }
  }
}
