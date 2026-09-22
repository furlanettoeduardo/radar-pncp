package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The one JSON reader this adapter uses, so that every part of it sees numbers the same way.
 *
 * <p>Floats are read as {@code BigDecimal} rather than {@code double}. Reading a monetary value
 * through a double loses the literal PNCP sent before anything downstream can normalise it, and a
 * hash computed over a value that already went through a double is a hash of an approximation.
 */
public final class PncpJson {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private PncpJson() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }
}
