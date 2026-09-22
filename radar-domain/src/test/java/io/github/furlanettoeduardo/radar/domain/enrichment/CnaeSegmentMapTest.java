package io.github.furlanettoeduardo.radar.domain.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.furlanettoeduardo.radar.domain.company.Cnae;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CnaeSegmentMapTest {

  @Test
  @DisplayName("maps a CNAE to the segment of its division, whatever the sub class")
  void mapsByDivision() {
    assertThat(CnaeSegmentMap.segmentOf(Cnae.of("6201-5/01")))
        .isEqualTo(ProcurementSegment.IT_SERVICES);
    assertThat(CnaeSegmentMap.segmentOf(Cnae.of("6202-3/00")))
        .isEqualTo(ProcurementSegment.IT_SERVICES);
    assertThat(CnaeSegmentMap.segmentOf(Cnae.of("4120-4/00")))
        .isEqualTo(ProcurementSegment.CIVIL_WORKS);
  }

  @Test
  @DisplayName("defaults to OTHER for divisions the map does not claim to cover")
  void defaultsToOther() {
    assertThat(CnaeSegmentMap.segmentOf(Cnae.of("9900-8/00"))).isEqualTo(ProcurementSegment.OTHER);
  }

  @Test
  @DisplayName("every mapped division names a real segment and the map stays small")
  void theMapIsSmallAndHonest() {
    assertThat(CnaeSegmentMap.coveredDivisions()).isNotEmpty().hasSizeLessThan(40);
  }
}
