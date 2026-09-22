package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mapper is where the recorded samples stop being evidence and start being a contract. Every
 * assertion here traces to a field that exists in docs/samples.
 */
class PncpProcurementMapperTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final PncpProcurementMapper mapper = new PncpProcurementMapper();

  @Test
  @DisplayName("maps a recorded notice into the domain, converting time at this boundary")
  void mapsARecordedNotice() {
    MappingResult result = mapper.map(firstRecordedNotice());

    assertThat(result).isInstanceOf(MappingResult.Mapped.class);
    Procurement procurement = ((MappingResult.Mapped) result).fetched().procurement();

    assertThat(procurement.controlNumber().value()).isEqualTo("44935278000126-1-000343/2025");
    assertThat(procurement.objectDescription()).startsWith("AQUISIÇÃO DE BRINQUEDOS PEDAGÓGICOS");
    assertThat(procurement.state()).isEqualTo(BrazilianState.SP);
    assertThat(procurement.estimatedValue())
        .contains(new MonetaryValue(new BigDecimal("14785.32")));
    assertThat(procurement.modality().code()).isEqualTo(6);
    assertThat(procurement.modality().name()).isEqualTo("Pregão - Eletrônico");
    assertThat(procurement.publishedAt()).isEqualTo(Instant.parse("2026-09-01T17:20:41Z"));
    assertThat(procurement.proposalOpensAt()).isEqualTo(Instant.parse("2026-09-02T11:00:00Z"));
    assertThat(procurement.proposalClosesAt()).isEqualTo(Instant.parse("2026-09-21T20:30:00Z"));
  }

  @Test
  @DisplayName("carries the raw payload, its hash and the cheap change detector")
  void carriesTheRawPayloadAndItsHash() {
    MappingResult.Mapped mapped = (MappingResult.Mapped) mapper.map(firstRecordedNotice());

    assertThat(mapped.fetched().rawPayload()).contains("numeroControlePNCP");
    assertThat(mapped.fetched().procurement().sourcePayloadHash())
        .isEqualTo(CanonicalJsonHash.of(firstRecordedNotice()));
    assertThat(mapped.fetched().procurement().sourceUpdatedAt())
        .contains(Instant.parse("2026-09-01T17:22:01Z"));
  }

  @Test
  @DisplayName("an absent estimated value is legal: that is what a sigiloso budget looks like")
  void anAbsentEstimatedValueIsLegal() {
    assertThat(mapEmptyValue(withoutField("valorTotalEstimado"))).isEmpty();
    assertThat(mapEmptyValue(withNull("valorTotalEstimado"))).isEmpty();
  }

  @Test
  @DisplayName("an unknown modality code maps fine, which is why Modality is not an enum")
  void anUnknownModalityCodeIsLegal() {
    ObjectNode notice = firstRecordedNoticeCopy();
    notice.put("modalidadeId", 99);
    notice.put("modalidadeNome", "Modalidade Que Ainda Nao Existia");

    MappingResult result = mapper.map(notice);

    assertThat(result).isInstanceOf(MappingResult.Mapped.class);
    assertThat(((MappingResult.Mapped) result).fetched().procurement().modality().code())
        .isEqualTo(99);
  }

  @Test
  @DisplayName("a missing identity is rejected, naming the field, not mapped to a placeholder")
  void rejectsAMissingControlNumber() {
    assertRejectedOn(withoutField("numeroControlePNCP"), "numeroControlePNCP");
  }

  @Test
  @DisplayName("a blank object is rejected: an empty description is not a procurement")
  void rejectsABlankObject() {
    ObjectNode notice = firstRecordedNoticeCopy();
    notice.put("objetoCompra", "   ");

    assertRejectedOn(notice, "objetoCompra");
  }

  @Test
  @DisplayName("a state PNCP invented is rejected rather than dropped to a default")
  void rejectsAnUnknownState() {
    ObjectNode notice = firstRecordedNoticeCopy();
    ((ObjectNode) notice.get("unidadeOrgao")).put("ufSigla", "XX");

    assertRejectedOn(notice, "unidadeOrgao.ufSigla");
  }

  @Test
  @DisplayName("a missing closing date is rejected: the deadline rule cannot run without it")
  void rejectsAMissingClosingDate() {
    assertRejectedOn(withoutField("dataEncerramentoProposta"), "dataEncerramentoProposta");
  }

  @Test
  @DisplayName("a missing opening or publication date is rejected too")
  void rejectsOtherMissingDates() {
    assertRejectedOn(withoutField("dataAberturaProposta"), "dataAberturaProposta");
    assertRejectedOn(withoutField("dataPublicacaoPncp"), "dataPublicacaoPncp");
  }

  @Test
  @DisplayName("replacement characters are rejected, not persisted as corrupted text")
  void rejectsMojibake() {
    ObjectNode notice = firstRecordedNoticeCopy();
    notice.put("objetoCompra", "AQUISI�AO DE BRINQUEDOS");

    MappingResult result = mapper.map(notice);

    assertThat(result)
        .isInstanceOfSatisfying(
            MappingResult.Rejected.class,
            rejected -> {
              assertThat(rejected.field()).isEqualTo("objetoCompra");
              assertThat(rejected.reason()).containsIgnoringCase("replacement character");
            });
  }

  @Test
  @DisplayName("a missing change detector is legal: it is a hint, not domain data")
  void anAbsentChangeDetectorIsLegal() {
    MappingResult result = mapper.map(withoutField("dataAtualizacaoGlobal"));

    assertThat(result).isInstanceOf(MappingResult.Mapped.class);
    assertThat(((MappingResult.Mapped) result).fetched().procurement().sourceUpdatedAt()).isEmpty();
  }

  @Test
  @DisplayName("a rejection still names the notice it came from, so it can be chased")
  void aRejectionNamesTheNotice() {
    MappingResult.Rejected rejected =
        (MappingResult.Rejected) mapper.map(withoutField("dataEncerramentoProposta"));

    assertThat(rejected.controlNumber()).isEqualTo("44935278000126-1-000343/2025");
  }

  @Test
  @DisplayName("a rejection says which kind of absence it was, since they have different causes")
  void aRejectionDistinguishesAbsentFromNullFromBlank() {
    ObjectNode blank = firstRecordedNoticeCopy();
    blank.put("dataEncerramentoProposta", "  ");

    assertThat(
            ((MappingResult.Rejected) mapper.map(withoutField("dataEncerramentoProposta")))
                .reason())
        .isEqualTo("required field is absent from the payload");
    assertThat(((MappingResult.Rejected) mapper.map(withNull("dataEncerramentoProposta"))).reason())
        .isEqualTo("required field is present but null");
    assertThat(((MappingResult.Rejected) mapper.map(blank)).reason())
        .isEqualTo("required field is present but blank");
  }

  private void assertRejectedOn(JsonNode notice, String expectedField) {
    assertThat(mapper.map(notice))
        .isInstanceOfSatisfying(
            MappingResult.Rejected.class,
            rejected -> assertThat(rejected.field()).isEqualTo(expectedField));
  }

  private java.util.Optional<MonetaryValue> mapEmptyValue(JsonNode notice) {
    MappingResult result = mapper.map(notice);
    assertThat(result).isInstanceOf(MappingResult.Mapped.class);
    return ((MappingResult.Mapped) result).fetched().procurement().estimatedValue();
  }

  private static ObjectNode withoutField(String field) {
    ObjectNode notice = firstRecordedNoticeCopy();
    notice.remove(field);
    return notice;
  }

  private static ObjectNode withNull(String field) {
    ObjectNode notice = firstRecordedNoticeCopy();
    notice.putNull(field);
    return notice;
  }

  private static ObjectNode firstRecordedNoticeCopy() {
    return ((ObjectNode) firstRecordedNotice()).deepCopy();
  }

  private static JsonNode firstRecordedNotice() {
    try {
      return JSON.readTree(SampleFixtures.read("contratacoes-proposta.json")).get("data").get(0);
    } catch (Exception cause) {
      throw new IllegalStateException("could not read the recorded sample", cause);
    }
  }
}
