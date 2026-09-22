package io.github.furlanettoeduardo.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcurementDiscoveredTest {

  private static final String CONTROL_NUMBER = "44935278000126-1-000343/2025";
  private static final String HASH = "0f5d1a5b1c2f4e6a8b9c0d1e2f3a4b5c";
  private static final String PAYLOAD = "{\"numeroControlePNCP\":\"44935278000126-1-000343/2025\"}";
  private static final String UPDATED_AT = "2026-09-01T17:22:01Z";

  @Test
  @DisplayName("a newly published message carries the current schema version")
  void carriesTheCurrentSchemaVersion() {
    ProcurementDiscovered message =
        ProcurementDiscovered.of(CONTROL_NUMBER, HASH, UPDATED_AT, PAYLOAD);

    assertThat(message.schemaVersion()).isEqualTo(ProcurementDiscovered.CURRENT_SCHEMA_VERSION);
    assertThat(message.pncpControlNumber()).isEqualTo(CONTROL_NUMBER);
    assertThat(message.contentHash()).isEqualTo(HASH);
    assertThat(message.sourceUpdatedAt()).isEqualTo(UPDATED_AT);
    assertThat(message.rawPayload()).isEqualTo(PAYLOAD);
  }

  @Test
  @DisplayName("an absent source timestamp is legal: PNCP does not always publish one")
  void anAbsentSourceTimestampIsLegal() {
    ProcurementDiscovered message = ProcurementDiscovered.of(CONTROL_NUMBER, HASH, null, PAYLOAD);

    assertThat(message.sourceUpdatedAt()).isNull();
  }

  @Test
  @DisplayName("a present but blank source timestamp is a bug, not an absence")
  void aBlankSourceTimestampIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "   ", PAYLOAD));
  }

  @Test
  @DisplayName(
      "the timestamp must be a canonical UTC instant, which is the whole point of a string")
  void acceptsOnlyACanonicalUtcInstant() {
    assertThat(
            ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "2026-09-01T17:22:01Z", PAYLOAD)
                .sourceUpdatedAt())
        .isEqualTo("2026-09-01T17:22:01Z");
    assertThat(
            ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "2026-09-01T17:22:01.500Z", PAYLOAD)
                .sourceUpdatedAt())
        .isEqualTo("2026-09-01T17:22:01.500Z");
  }

  @Test
  @DisplayName(
      "a naive timestamp is rejected: it is exactly the shape PNCP publishes and we convert")
  void rejectsANaiveTimestamp() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "2026-09-01T17:22:01", PAYLOAD))
        .withMessageContaining("2026-09-01T17:22:01");
  }

  @Test
  @DisplayName("epoch seconds are rejected, whatever a serializer might prefer")
  void rejectsEpochSeconds() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "1756747321", PAYLOAD));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "1756747321.000000000", PAYLOAD));
  }

  @Test
  @DisplayName("a non-UTC offset is rejected: the same instant must have exactly one spelling")
  void rejectsANonUtcOffset() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ProcurementDiscovered.of(
                    CONTROL_NUMBER, HASH, "2026-09-01T14:22:01-03:00", PAYLOAD));
  }

  @Test
  @DisplayName("a one-digit fraction is rejected: Java renders fractions in groups of three")
  void rejectsANonCanonicalFractionWidth() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ProcurementDiscovered.of(CONTROL_NUMBER, HASH, "2026-09-01T17:22:01.5Z", PAYLOAD));
  }

  @Test
  @DisplayName(
      "padded fractional zeros are rejected, since they are a second spelling of one instant")
  void rejectsNonCanonicalFractionalZeros() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ProcurementDiscovered.of(
                    CONTROL_NUMBER, HASH, "2026-09-01T17:22:01.000Z", PAYLOAD));
  }

  @Test
  @DisplayName("a message without an identity cannot be acted on")
  void rejectsABlankControlNumber() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ProcurementDiscovered.of("  ", HASH, UPDATED_AT, PAYLOAD));
    assertThatNullPointerException()
        .isThrownBy(() -> ProcurementDiscovered.of(null, HASH, UPDATED_AT, PAYLOAD));
  }

  @Test
  @DisplayName("a message without a content hash cannot be deduplicated")
  void rejectsABlankContentHash() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ProcurementDiscovered.of(CONTROL_NUMBER, " ", UPDATED_AT, PAYLOAD));
  }

  @Test
  @DisplayName("a message without a payload carries nothing worth sending")
  void rejectsABlankPayload() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ProcurementDiscovered.of(CONTROL_NUMBER, HASH, UPDATED_AT, ""));
  }

  @Test
  @DisplayName("schema versions start at one: a zero or negative version is not a version")
  void rejectsANonPositiveSchemaVersion() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ProcurementDiscovered(0, CONTROL_NUMBER, HASH, UPDATED_AT, PAYLOAD));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ProcurementDiscovered(-1, CONTROL_NUMBER, HASH, UPDATED_AT, PAYLOAD));
  }

  @Test
  @DisplayName("an older schema version can still be constructed, so a consumer can read one")
  void olderVersionsRemainConstructible() {
    ProcurementDiscovered older =
        new ProcurementDiscovered(1, CONTROL_NUMBER, HASH, UPDATED_AT, PAYLOAD);

    assertThat(older.schemaVersion()).isEqualTo(1);
  }
}
