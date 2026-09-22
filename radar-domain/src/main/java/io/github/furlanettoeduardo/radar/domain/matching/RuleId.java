package io.github.furlanettoeduardo.radar.domain.matching;

/**
 * Names the scoring rules, so weights can be data keyed by rule rather than constants living inside
 * the rules themselves. Adding a rule without giving it a weight fails fast, because a weighting
 * scheme must cover every value here.
 */
public enum RuleId {
  KEYWORD,
  SEGMENT,
  ESTIMATED_VALUE,
  STATE,
  PROPOSAL_DEADLINE
}
