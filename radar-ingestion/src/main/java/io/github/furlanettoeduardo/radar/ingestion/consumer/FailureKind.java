package io.github.furlanettoeduardo.radar.ingestion.consumer;

/**
 * Why a message failed, which decides how patient the consumer is with it.
 *
 * <p>A redrive policy counts receives and cannot see the reason behind any of them. Without this
 * distinction a database maintenance window consumes a message's three receives exactly as a
 * malformed payload does, and sends good messages to the dead letter queue.
 */
public enum FailureKind {
  /** The message will fail the same way forever. Let it reach the dead letter queue quickly. */
  POISON,
  /** Nothing is wrong with the message. Wait out whatever is wrong with us. */
  TRANSIENT
}
