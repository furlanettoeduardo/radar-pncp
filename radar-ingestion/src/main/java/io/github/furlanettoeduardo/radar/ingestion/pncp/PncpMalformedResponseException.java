package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.Serial;

/**
 * PNCP answered with something this client cannot read. Not retried: a contract change or a
 * corrupted response will be just as unreadable the second time, and retrying would turn a loud
 * failure into a slow one.
 */
public class PncpMalformedResponseException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PncpMalformedResponseException(String message) {
    super(message);
  }

  public PncpMalformedResponseException(String message, Throwable cause) {
    super(message, cause);
  }
}
