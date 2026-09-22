package io.github.furlanettoeduardo.radar.shared;

import java.util.Objects;

/**
 * A procurement that ingestion found on PNCP and has not yet handed on.
 *
 * <p>Carries the payload rather than a pointer to it. A recorded notice is about 2.6 KB against
 * SQS's 256 KB limit, so sending it whole costs nothing and saves the consumer a second call to an
 * API that has already been shown to have bad days.
 *
 * <h2>Why the timestamp is a String</h2>
 *
 * <p>{@code sourceUpdatedAt} is an ISO-8601 instant held as text, not as {@code java.time.Instant}.
 * That is deliberate. An {@code Instant} serialises as {@code "2026-09-01T17:22:01Z"} or as {@code
 * 1756747321.000000000} depending on a Jackson feature flag, so a typed field would make the wire
 * format depend on serializer configuration that neither side declares. A versioned contract cannot
 * afford that: the shape has to be decided here, once, and be the same for every producer and
 * consumer that ever reads it. Parsing is the adapter's job.
 *
 * <p>{@code null} means PNCP published no {@code dataAtualizacaoGlobal} for this notice, which is
 * legal. A present but blank value is a producer bug and is rejected.
 *
 * <h2>Schema version</h2>
 *
 * <p>Every message states the version it was written against, so a consumer never has to infer it
 * from the fields present. What a consumer does with a version it does not know, and what counts as
 * a breaking change, is in {@code docs/adr/0009-message-contract-compatibility.md}.
 */
public record ProcurementDiscovered(
    int schemaVersion,
    String pncpControlNumber,
    String contentHash,
    String sourceUpdatedAt,
    String rawPayload) {

  /** The version a producer writes today. Consumers must still be able to read older ones. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ProcurementDiscovered {
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schema versions start at 1 but was " + schemaVersion);
    }
    requireText(pncpControlNumber, "pncpControlNumber");
    requireText(contentHash, "contentHash");
    requireText(rawPayload, "rawPayload");
    if (sourceUpdatedAt != null && sourceUpdatedAt.isBlank()) {
      // Absent is legal and means PNCP published none. Blank means somebody sent an empty
      // string instead, which is a bug worth failing on rather than silently treating as absent.
      throw new IllegalArgumentException(
          "sourceUpdatedAt must be absent or a timestamp, never blank");
    }
  }

  /** Builds a message at the current schema version. */
  public static ProcurementDiscovered of(
      String pncpControlNumber, String contentHash, String sourceUpdatedAt, String rawPayload) {
    return new ProcurementDiscovered(
        CURRENT_SCHEMA_VERSION, pncpControlNumber, contentHash, sourceUpdatedAt, rawPayload);
  }

  private static void requireText(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
