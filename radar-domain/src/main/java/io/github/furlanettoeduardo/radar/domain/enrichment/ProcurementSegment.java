package io.github.furlanettoeduardo.radar.domain.enrichment;

/**
 * The closed vocabulary an enrichment may classify a procurement into.
 *
 * <p>Closed on purpose. Asking a model for a CNAE code invites a fluent, well formed, wrong answer
 * out of thousands of codes in a hierarchy it can confabulate. Fifteen values can be enumerated in
 * a prompt, validated on the way back, and argued about by a human.
 *
 * <p>See {@code docs/adr/0004-closed-segment-vocabulary-instead-of-cnae-inference.md}.
 */
public enum ProcurementSegment {
  /** Construction and engineering works. */
  CIVIL_WORKS,
  /** Building inputs: hydraulic, electrical, structural. Not the works themselves. */
  CONSTRUCTION_MATERIALS,
  IT_SERVICES,
  IT_HARDWARE,
  OFFICE_SUPPLIES,
  FOOD_AND_CATERING,
  HEALTHCARE_SUPPLIES,
  VEHICLES_AND_FLEET,
  CLEANING_AND_MAINTENANCE,
  FURNITURE,
  /** Teaching materials and training services alike. */
  EDUCATION_AND_TEACHING_MATERIALS,
  SECURITY_SERVICES,
  ENERGY_AND_UTILITIES,
  /** Legal, accounting, engineering and management consultancy. */
  PROFESSIONAL_SERVICES,
  /** Everything the vocabulary does not cover. A real answer, not a failure. */
  OTHER
}
