package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.procurement.Modality;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.io.Serial;
import java.time.Instant;
import java.util.Optional;

/**
 * Turns one PNCP notice into a domain procurement, or explains why it could not.
 *
 * <p>Mapping is done over the parsed tree rather than by binding to a DTO, deliberately. Binding
 * silently turns an absent field into a null, which is exactly the distinction this class exists to
 * make: for some fields absence is legal and meaningful, for others it means the notice is not a
 * procurement at all.
 *
 * <p>The recorded samples are thin — five notices, one state, one modality, nothing null — so
 * nothing here assumes a field is present because it happened to be present there. Absence is
 * decided per field:
 *
 * <ul>
 *   <li><b>Illegal</b>: {@code numeroControlePNCP}, {@code objetoCompra}, {@code
 *       unidadeOrgao.ufSigla}, {@code modalidadeId}, {@code modalidadeNome}, {@code
 *       dataPublicacaoPncp}, {@code dataAberturaProposta}, {@code dataEncerramentoProposta}. Any of
 *       these missing and the notice is rejected, named and counted. A half built procurement is
 *       never produced.
 *   <li><b>Legal</b>: {@code valorTotalEstimado}, absent when the budget is sigiloso, which the
 *       domain already models as an optional value. And {@code dataAtualizacaoGlobal}, a hint
 *       rather than domain data.
 * </ul>
 *
 * <p>An unknown modality code maps without complaint. That is the payoff for modelling modality as
 * a record rather than an enum: PNCP may add one without this adapter needing to know.
 *
 * <p>Text is checked for U+FFFD, the Unicode replacement character. Its presence means bytes were
 * decoded with the wrong charset somewhere upstream, and a notice whose object reads {@code
 * AQUISI?AO} is corrupt. Rejecting it loudly here is far cheaper than discovering it in a user
 * interface months later.
 */
public final class PncpProcurementMapper {

  private static final char REPLACEMENT_CHARACTER = '�';

  public MappingResult map(JsonNode notice) {
    String controlNumber =
        notice.hasNonNull("numeroControlePNCP")
            ? notice.get("numeroControlePNCP").asText()
            : MappingResult.Rejected.UNKNOWN_NOTICE;
    try {
      Procurement procurement =
          new Procurement(
              new PncpControlNumber(requiredText(notice, "numeroControlePNCP")),
              requiredText(notice, "objetoCompra"),
              requiredState(notice),
              optionalMoney(notice, "valorTotalEstimado"),
              new Modality(
                  requiredInt(notice, "modalidadeId"), requiredText(notice, "modalidadeNome")),
              requiredInstant(notice, "dataPublicacaoPncp"),
              requiredInstant(notice, "dataAberturaProposta"),
              requiredInstant(notice, "dataEncerramentoProposta"),
              CanonicalJsonHash.of(notice));

      return new MappingResult.Mapped(
          new FetchedProcurement(
              procurement, notice.toString(), optionalInstant(notice, "dataAtualizacaoGlobal")));

    } catch (FieldRejection rejection) {
      return new MappingResult.Rejected(controlNumber, rejection.field(), rejection.getMessage());
    } catch (IllegalArgumentException invariant) {
      // A domain invariant refused the notice, for example a proposal window that closes before it
      // opens. That is a rejection, not a crash, and not a half built object either.
      return new MappingResult.Rejected(controlNumber, "notice", invariant.getMessage());
    }
  }

  private static String requiredText(JsonNode notice, String field) {
    JsonNode value = notice.get(field);
    // Told apart on purpose: "absent" and "null" send whoever reads the log to different places,
    // one to the query and one to the record.
    if (value == null) {
      throw new FieldRejection(field, "required field is absent from the payload");
    }
    if (value.isNull()) {
      throw new FieldRejection(field, "required field is present but null");
    }
    if (value.asText().isBlank()) {
      throw new FieldRejection(field, "required field is present but blank");
    }
    String text = value.asText();
    if (text.indexOf(REPLACEMENT_CHARACTER) >= 0) {
      throw new FieldRejection(
          field, "the text contains a Unicode replacement character, so it was decoded wrongly");
    }
    return text;
  }

  private static int requiredInt(JsonNode notice, String field) {
    JsonNode value = notice.get(field);
    if (value == null) {
      throw new FieldRejection(field, "required field is absent from the payload");
    }
    if (value.isNull()) {
      throw new FieldRejection(field, "required field is present but null");
    }
    if (!value.canConvertToInt()) {
      throw new FieldRejection(field, "required field is not a whole number: " + value.asText());
    }
    return value.asInt();
  }

  private static BrazilianState requiredState(JsonNode notice) {
    String field = "unidadeOrgao.ufSigla";
    JsonNode unit = notice.get("unidadeOrgao");
    if (unit == null || unit.isNull()) {
      throw new FieldRejection("unidadeOrgao", "the field is absent or null");
    }
    String uf = requiredText(unit, "ufSigla");
    try {
      return BrazilianState.valueOf(uf.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new FieldRejection(field, "not a Brazilian state code: " + uf);
    }
  }

  private static Instant requiredInstant(JsonNode notice, String field) {
    String raw = requiredText(notice, field);
    try {
      return PncpTimestamps.toInstant(raw);
    } catch (IllegalArgumentException unreadable) {
      throw new FieldRejection(field, unreadable.getMessage());
    }
  }

  private static Optional<Instant> optionalInstant(JsonNode notice, String field) {
    JsonNode value = notice.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(PncpTimestamps.toInstant(value.asText()));
    } catch (IllegalArgumentException unreadable) {
      throw new FieldRejection(field, unreadable.getMessage());
    }
  }

  /**
   * Absent means the budget is sigiloso, which the domain models rather than treats as an error.
   */
  private static Optional<MonetaryValue> optionalMoney(JsonNode notice, String field) {
    JsonNode value = notice.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    try {
      if (!value.isNumber()) {
        throw new FieldRejection(field, "not a number: " + value.asText());
      }
      // decimalValue on a payload parsed by PncpJson is the literal PNCP sent, not a double
      // that has already lost it.
      return Optional.of(new MonetaryValue(value.decimalValue()));
    } catch (NumberFormatException notANumber) {
      throw new FieldRejection(field, "not a number: " + value.asText());
    } catch (IllegalArgumentException negative) {
      throw new FieldRejection(field, negative.getMessage());
    }
  }

  /** Internal control flow: carries the offending field out to the single catch in {@link #map}. */
  private static final class FieldRejection extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient String field;

    private FieldRejection(String field, String reason) {
      super(reason, null, false, false);
      this.field = field;
    }

    private String field() {
      return field;
    }
  }
}
