package io.github.furlanettoeduardo.radar.ingestion.consumer;

/**
 * A message written by a newer producer than this consumer understands.
 *
 * <p>Poison by definition rather than by classification: a consumer that cannot read version 2 will
 * still not be able to read it in ten minutes. It belongs on the dead letter queue, where a redrive
 * after the consumer is upgraded replays it. See ADR 0009.
 */
public final class UnsupportedSchemaVersionException extends RuntimeException {

  public UnsupportedSchemaVersionException(int received, int supported) {
    super(
        ("message schema version %d is newer than the %d this consumer understands; it goes to the "
                + "dead letter queue so it can be redriven after an upgrade rather than dropped")
            .formatted(received, supported));
  }
}
