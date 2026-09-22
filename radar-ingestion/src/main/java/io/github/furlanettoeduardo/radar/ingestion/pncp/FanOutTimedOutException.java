package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.Serial;

/**
 * The whole fan out took longer than its deadline and was stopped.
 *
 * <p>Per request timeouts bound one call; they do not bound an invocation. At the configured cap
 * the arithmetic is unkind: 500 pages, three attempts each, a ten second read timeout, and a
 * degraded PNCP keeps one run alive for well over half an hour. Under a periodic scheduler that is
 * how runs start overlapping.
 */
public class FanOutTimedOutException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public FanOutTimedOutException(String message) {
    super(message);
  }
}
