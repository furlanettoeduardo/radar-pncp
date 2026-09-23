package io.github.furlanettoeduardo.radar.ingestion.discovery;

/**
 * Who asked for a chunk.
 *
 * <p>The distinction earns its place twice, and both times it is about not lying to an operator. A
 * MANUAL chunk is never reported as a coverage gap, because it is the answer to a gap rather than
 * another one. And a MANUAL chunk never extends how far back discovery is considered responsible:
 * backfilling one date from 2020 must not make every date since 2020 look like something we lost.
 */
public enum ChunkOrigin {
  SCHEDULED,
  MANUAL
}
