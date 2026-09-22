package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.Serial;

/** PNCP could not answer: a 5xx, a timeout, or an unreachable host. Worth retrying. */
public class PncpUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PncpUnavailableException(String message) {
    super(message);
  }

  public PncpUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
