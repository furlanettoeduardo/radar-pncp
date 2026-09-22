package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
  @DisplayName("accented text hashes by its characters, not by how the bytes were framed")
  void accentedTextIsStable() {
    assertThat(CanonicalJsonHash.of("{\"objetoCompra\":\"AQUISIÇÃO DE BRINQUEDOS\"}"))
        .isEqualTo(CanonicalJsonHash.of("{ \"objetoCompra\" : \"AQUISIÇÃO DE BRINQUEDOS\" }"));
  }

  @Test
  @DisplayName("refuses to hash something that is not JSON rather than hashing the error")
  void refusesNonJson() {
    assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJsonHash.of("not json"));
    assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJsonHash.of(""));
  }
}
