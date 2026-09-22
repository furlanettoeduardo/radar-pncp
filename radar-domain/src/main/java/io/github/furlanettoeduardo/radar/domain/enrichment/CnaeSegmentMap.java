package io.github.furlanettoeduardo.radar.domain.enrichment;

import io.github.furlanettoeduardo.radar.domain.company.Cnae;
import java.util.Map;
import java.util.Set;

/**
 * Maps a company's declared CNAE onto the closed segment vocabulary, at division level.
 *
 * <p>Deliberately small. It claims the divisions where the mapping is uncontroversial and answers
 * {@link ProcurementSegment#OTHER} everywhere else. An exhaustive table would be a table of
 * guesses, and a wrong mapping here is invisible: it silently removes a company's matches.
 */
public final class CnaeSegmentMap {

  private static final Map<String, ProcurementSegment> BY_DIVISION =
      Map.ofEntries(
          Map.entry("41", ProcurementSegment.CIVIL_WORKS),
          Map.entry("42", ProcurementSegment.CIVIL_WORKS),
          Map.entry("43", ProcurementSegment.CIVIL_WORKS),
          Map.entry("16", ProcurementSegment.CONSTRUCTION_MATERIALS),
          Map.entry("23", ProcurementSegment.CONSTRUCTION_MATERIALS),
          Map.entry("25", ProcurementSegment.CONSTRUCTION_MATERIALS),
          Map.entry("62", ProcurementSegment.IT_SERVICES),
          Map.entry("63", ProcurementSegment.IT_SERVICES),
          Map.entry("26", ProcurementSegment.IT_HARDWARE),
          Map.entry("17", ProcurementSegment.OFFICE_SUPPLIES),
          Map.entry("18", ProcurementSegment.OFFICE_SUPPLIES),
          Map.entry("10", ProcurementSegment.FOOD_AND_CATERING),
          Map.entry("56", ProcurementSegment.FOOD_AND_CATERING),
          Map.entry("21", ProcurementSegment.HEALTHCARE_SUPPLIES),
          Map.entry("32", ProcurementSegment.HEALTHCARE_SUPPLIES),
          Map.entry("86", ProcurementSegment.HEALTHCARE_SUPPLIES),
          Map.entry("29", ProcurementSegment.VEHICLES_AND_FLEET),
          Map.entry("45", ProcurementSegment.VEHICLES_AND_FLEET),
          Map.entry("49", ProcurementSegment.VEHICLES_AND_FLEET),
          Map.entry("81", ProcurementSegment.CLEANING_AND_MAINTENANCE),
          Map.entry("31", ProcurementSegment.FURNITURE),
          Map.entry("85", ProcurementSegment.EDUCATION_AND_TEACHING_MATERIALS),
          Map.entry("80", ProcurementSegment.SECURITY_SERVICES),
          Map.entry("35", ProcurementSegment.ENERGY_AND_UTILITIES),
          Map.entry("36", ProcurementSegment.ENERGY_AND_UTILITIES),
          Map.entry("69", ProcurementSegment.PROFESSIONAL_SERVICES),
          Map.entry("70", ProcurementSegment.PROFESSIONAL_SERVICES),
          Map.entry("71", ProcurementSegment.PROFESSIONAL_SERVICES));

  private CnaeSegmentMap() {}

  public static ProcurementSegment segmentOf(Cnae cnae) {
    return BY_DIVISION.getOrDefault(cnae.division(), ProcurementSegment.OTHER);
  }

  /** The divisions this map claims to cover. Everything else answers OTHER. */
  public static Set<String> coveredDivisions() {
    return BY_DIVISION.keySet();
  }
}
