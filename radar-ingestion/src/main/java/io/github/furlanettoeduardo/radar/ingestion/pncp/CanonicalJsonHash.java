package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A content hash for a PNCP payload, stable against anything that is not content.
 *
 * <p>The hash is SHA-256 over a canonical rendering of the JSON: object keys sorted recursively,
 * array order preserved because order in an array <em>is</em> content, and no insignificant
 * whitespace. Hashing the bytes as received would be simpler and wrong: PNCP reformatting its
 * output or emitting fields in a different order would present every notice in the catalogue as
 * changed.
 *
 * <p>Numbers are normalised through {@code BigDecimal}, so {@code 10000}, {@code 10000.00} and
 * {@code 1e4} are one value with one hash. Without that, PNCP changing how it formats a decimal
 * would present the entire corpus as modified and reprocess all of it, which is the expensive
 * failure this hash exists to prevent.
 *
 * <p>A field whose value is null is dropped, so an explicit null and an absent field hash the same.
 * That is not a stylistic choice: the mapper already treats those two identically for every field,
 * and a change detector that disagrees with the reader about what is the same is worse than no
 * change detector. Nulls <em>inside arrays</em> are kept, because there position is content.
 *
 * <p>This is the authoritative change detector. {@code dataAtualizacaoGlobal} is the cheap one,
 * used to avoid computing this at all when PNCP already says nothing moved.
 */
public final class CanonicalJsonHash {

  private CanonicalJsonHash() {}

  public static String of(String rawJson) {
    return of(parse(rawJson));
  }

  public static String of(JsonNode payload) {
    StringBuilder canonical = new StringBuilder();
    write(payload, canonical);
    return sha256Hex(canonical.toString());
  }

  private static JsonNode parse(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      throw new IllegalArgumentException("cannot hash a blank payload");
    }
    try {
      return PncpJson.mapper().readTree(rawJson);
    } catch (JsonProcessingException cause) {
      throw new IllegalArgumentException("cannot hash a payload that is not JSON", cause);
    }
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        if (!field.getValue().isNull()) {
          fields.add(field);
        }
      }
      // String.compareTo is a total order over UTF-16 code units, which is all that is needed:
      // deterministic, locale independent, and identical on every JVM.
      fields.sort(Map.Entry.comparingByKey());

      out.append('{');
      for (int i = 0; i < fields.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        writeString(fields.get(i).getKey(), out);
        out.append(':');
        write(fields.get(i).getValue(), out);
      }
      out.append('}');
      return;
    }
    if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        write(node.get(i), out);
      }
      out.append(']');
      return;
    }
    if (node.isTextual()) {
      writeString(node.textValue(), out);
      return;
    }
    if (node.isNumber()) {
      out.append(normalise(node.decimalValue()));
      return;
    }
    out.append(node.asText());
  }

  /**
   * One rendering per numeric value, whatever notation PNCP used to write it. {@code
   * stripTrailingZeros} collapses 10000.00 and 1e4 onto 10000, and {@code toPlainString} keeps very
   * large and very small magnitudes out of exponent notation so that they normalise too.
   */
  private static String normalise(BigDecimal number) {
    return number.stripTrailingZeros().toPlainString();
  }

  /** Quoted so that a value cannot be confused with a delimiter or with a neighbouring field. */
  private static void writeString(String value, StringBuilder out) {
    out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
  }

  private static String sha256Hex(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
    }
  }
}
