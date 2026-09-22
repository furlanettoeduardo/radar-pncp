package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.Serial;

/**
 * Internal signal: this job never ran because a sibling had already established that the upstream
 * is down.
 *
 * <p>Never reaches a caller. Whenever one of these is thrown, a real first failure has been
 * recorded, and that is what the fan out reports instead.
 */
final class FanOutAbandonedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  FanOutAbandonedException(String message) {
    super(message, null, false, false);
  }
}
