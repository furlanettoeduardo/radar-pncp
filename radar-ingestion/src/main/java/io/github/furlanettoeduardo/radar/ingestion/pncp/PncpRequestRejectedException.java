package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.Serial;

/**
 * PNCP refused the request: a 4xx. Never retried, because asking again identically will be refused
 * identically. The recorded {@code caso-erro} sample is one of these: the call omitted {@code
 * codigoModalidadeContratacao}, which PNCP requires.
 */
public class PncpRequestRejectedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PncpRequestRejectedException(String message) {
    super(message);
  }
}
