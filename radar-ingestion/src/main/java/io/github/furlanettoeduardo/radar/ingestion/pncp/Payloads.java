package io.github.furlanettoeduardo.radar.ingestion.pncp;

/** Keeps payloads out of messages and log lines at full length. */
final class Payloads {

  /** Enough to diagnose from, not enough to fill a small disk with. */
  static final int MAX_QUOTED = 500;

  private Payloads() {}

  static String quote(String payload) {
    if (payload == null || payload.isBlank()) {
      return "<empty body>";
    }
    String collapsed = payload.strip();
    return collapsed.length() <= MAX_QUOTED
        ? collapsed
        : collapsed.substring(0, MAX_QUOTED) + "... <truncated, " + collapsed.length() + " chars>";
  }
}
