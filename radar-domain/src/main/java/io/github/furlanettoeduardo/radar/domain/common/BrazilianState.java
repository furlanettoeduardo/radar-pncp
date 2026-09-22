package io.github.furlanettoeduardo.radar.domain.common;

/**
 * The 26 states and the Federal District, identified by the two letter code PNCP publishes in
 * {@code unidadeOrgao.ufSigla}.
 *
 * <p>This is an enum because the set is closed and defined outside this system. Modality is not,
 * which is why it is modelled differently.
 */
public enum BrazilianState {
  AC,
  AL,
  AP,
  AM,
  BA,
  CE,
  DF,
  ES,
  GO,
  MA,
  MT,
  MS,
  MG,
  PA,
  PB,
  PR,
  PE,
  PI,
  RJ,
  RN,
  RS,
  RO,
  RR,
  SC,
  SP,
  SE,
  TO
}
