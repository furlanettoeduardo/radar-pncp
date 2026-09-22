package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.Serial;

/**
 * The query would need more pages than the configured cap allows.
 *
 * <p>It throws rather than truncating. Partial data that looks complete is the failure this project
 * keeps designing against, and a caller that received half a day of notices with no way to tell
 * would go on to treat the missing half as absent from PNCP.
 *
 * <p>Hitting this means the window is too wide, not that the cap is too low. The fix is to chunk
 * the range; a cap that forces an operator to narrow a backfill is doing its job.
 */
public class PncpFanOutTooLargeException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PncpFanOutTooLargeException(String message) {
    super(message);
  }
}
