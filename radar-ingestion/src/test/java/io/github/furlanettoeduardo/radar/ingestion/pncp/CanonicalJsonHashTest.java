package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalJsonHashTest {

  @Test
  @DisplayName("key order does not change the hash, because it does not change the content")
  void keyOrderDoesNotMatter() {
    assertThat(CanonicalJsonHash.of("{\"a\":1,\"b\":2}"))
        .isEqualTo(CanonicalJsonHash.of("{\"b\":2,\"a\":1}"));
  }

  @Test
  @DisplayName("whitespace and indentation do not change the hash")
  void formattingDoesNotMatter() {
    assertThat(CanonicalJsonHash.of("{\"a\":1,\"b\":[1,2]}"))
        .isEqualTo(CanonicalJsonHash.of("{\n  \"a\" : 1,\n  \"b\" : [ 1, 2 ]\n}"));
  }

  @Test
  @DisplayName("nested objects are sorted all the way down")
  void sortingIsRecursive() {
    assertThat(CanonicalJsonHash.of("{\"x\":{\"p\":1,\"q\":2}}"))
        .isEqualTo(CanonicalJsonHash.of("{\"x\":{\"q\":2,\"p\":1}}"));
  }

  @Test
  @DisplayName("array order is content, so reordering an array changes the hash")
  void arrayOrderIsPreserved() {
    assertThat(CanonicalJsonHash.of("{\"a\":[1,2]}"))
        .isNotEqualTo(CanonicalJsonHash.of("{\"a\":[2,1]}"));
  }

  @Test
  @DisplayName("a changed value changes the hash")
  void contentChangesTheHash() {
    assertThat(CanonicalJsonHash.of("{\"valorTotalEstimado\":14785.32}"))
        .isNotEqualTo(CanonicalJsonHash.of("{\"valorTotalEstimado\":14785.33}"));
  }

  @Test
  @DisplayName("hashes a real recorded payload to 64 hex characters, repeatably")
  void hashesARecordedPayload() {
    String payload = SampleFixtures.read("contratacao-detalhe.json");

    String hash = CanonicalJsonHash.of(payload);

    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(hash).isEqualTo(CanonicalJsonHash.of(payload));
  }

  @Test
  @DisplayName("the same notice fetched twice hashes identically, however the response was framed")
  void theSameNoticeFetchedTwiceHashesIdentically() throws Exception {
    // What a first fetch returned.
    JsonNode firstFetch =
        PncpJson.mapper().readTree(SampleFixtures.read("contratacoes-publicacao.json")).get("data");

    // What a second fetch of the same notices could legitimately look like: identical content,
    // fields emitted in a different order, pretty printed. Nothing about the notices changed.
    String secondFetch =
        PncpJson.mapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(reverseKeys(firstFetch));
    JsonNode reparsed = PncpJson.mapper().readTree(secondFetch);

    for (int notice = 0; notice < firstFetch.size(); notice++) {
      assertThat(CanonicalJsonHash.of(reparsed.get(notice)))
          .as(
              "notice %d must hash the same on a second fetch, or every re-read looks like a"
                  + " change",
              notice)
          .isEqualTo(CanonicalJsonHash.of(firstFetch.get(notice)));
    }
  }

  /** Rebuilds the tree with every object's fields in the opposite order, content untouched. */
  private static JsonNode reverseKeys(JsonNode node) {
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.reverse(names);
      ObjectNode reordered = PncpJson.mapper().createObjectNode();
      names.forEach(name -> reordered.set(name, reverseKeys(node.get(name))));
      return reordered;
    }
    if (node.isArray()) {
      ArrayNode rebuilt = PncpJson.mapper().createArrayNode();
      node.forEach(child -> rebuilt.add(reverseKeys(child)));
      return rebuilt;
    }
    return node;
  }

  @Test
  @DisplayName("accented text hashes by its characters, not by how the bytes were framed")
  void accentedTextIsStable() {
    assertThat(CanonicalJsonHash.of("{\"objetoCompra\":\"AQUISIÇÃO DE BRINQUEDOS\"}"))
        .isEqualTo(CanonicalJsonHash.of("{ \"objetoCompra\" : \"AQUISIÇÃO DE BRINQUEDOS\" }"));
  }

  @Test
  @DisplayName("the same number written three ways hashes the same, or the corpus reprocesses")
  void numericFormattingDoesNotMatter() {
    String asInteger = CanonicalJsonHash.of("{\"valorTotalEstimado\":10000}");
    String withTrailingZeros = CanonicalJsonHash.of("{\"valorTotalEstimado\":10000.00}");
    String inScientificNotation = CanonicalJsonHash.of("{\"valorTotalEstimado\":1e4}");

    assertThat(asInteger).isEqualTo(withTrailingZeros).isEqualTo(inScientificNotation);
  }

  @Test
  @DisplayName("normalising numbers does not collapse genuinely different ones")
  void differentNumbersStillDiffer() {
    assertThat(CanonicalJsonHash.of("{\"a\":10000}"))
        .isNotEqualTo(CanonicalJsonHash.of("{\"a\":10000.01}"));
    assertThat(CanonicalJsonHash.of("{\"a\":0}")).isNotEqualTo(CanonicalJsonHash.of("{\"a\":0.1}"));
  }

  @Test
  @DisplayName("zero and negative values normalise without surprises")
  void zeroAndNegativesNormalise() {
    assertThat(CanonicalJsonHash.of("{\"a\":0}")).isEqualTo(CanonicalJsonHash.of("{\"a\":0.00}"));
    assertThat(CanonicalJsonHash.of("{\"a\":-1.50}"))
        .isEqualTo(CanonicalJsonHash.of("{\"a\":-1.5}"));
  }

  @Test
  @DisplayName("non ASCII keys sort by a total order, so input order still does not matter")
  void nonAsciiKeysSortDeterministically() {
    assertThat(CanonicalJsonHash.of("{\"ação\":1,\"abc\":2,\"órgão\":3}"))
        .isEqualTo(CanonicalJsonHash.of("{\"órgão\":3,\"abc\":2,\"ação\":1}"));
  }

  @Test
  @DisplayName("an explicit null and an absent field are the same thing, as they are to the mapper")
  void explicitNullMatchesAbsence() {
    assertThat(CanonicalJsonHash.of("{\"a\":1,\"valorTotalEstimado\":null}"))
        .isEqualTo(CanonicalJsonHash.of("{\"a\":1}"));
  }

  @Test
  @DisplayName("nulls nested deeper are normalised too")
  void nestedNullsAreNormalised() {
    assertThat(CanonicalJsonHash.of("{\"x\":{\"p\":1,\"q\":null}}"))
        .isEqualTo(CanonicalJsonHash.of("{\"x\":{\"p\":1}}"));
  }

  @Test
  @DisplayName("a null inside an array keeps its position, because position is content there")
  void nullsInArraysArePreserved() {
    assertThat(CanonicalJsonHash.of("{\"a\":[1,null,2]}"))
        .isNotEqualTo(CanonicalJsonHash.of("{\"a\":[1,2]}"));
  }

  @Test
  @DisplayName("a string that reads null is not a null")
  void aStringIsNotANull() {
    assertThat(CanonicalJsonHash.of("{\"a\":\"null\"}"))
        .isNotEqualTo(CanonicalJsonHash.of("{\"a\":null}"));
  }

  @Test
  @DisplayName("refuses to hash something that is not JSON rather than hashing the error")
  void refusesNonJson() {
    assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJsonHash.of("not json"));
    assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJsonHash.of(""));
  }
}
