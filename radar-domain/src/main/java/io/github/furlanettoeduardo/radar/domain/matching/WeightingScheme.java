package io.github.furlanettoeduardo.radar.domain.matching;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What each rule is worth. Data, injected into the engine, tunable without touching rule logic.
 *
 * <p>Weights must sum to exactly 100 and must cover every rule. That is what makes the 0 to 100
 * bound a property of construction rather than a clamp applied afterwards, and it means adding a
 * rule without deciding what it is worth fails immediately instead of quietly diluting the scale.
 */
public record WeightingScheme(Map<RuleId, Integer> weights) {

  private static final int TOTAL = 100;

  public WeightingScheme {
    Objects.requireNonNull(weights, "a weighting scheme must have weights");
    weights = Map.copyOf(weights);
    for (RuleId rule : RuleId.values()) {
      if (!weights.containsKey(rule)) {
        throw new IllegalArgumentException("no weight was given for rule " + rule);
      }
      if (weights.get(rule) < 0) {
        throw new IllegalArgumentException("a negative weight was given for rule " + rule);
      }
    }
    int sum = weights.values().stream().mapToInt(Integer::intValue).sum();
    if (sum != TOTAL) {
      throw new IllegalArgumentException("weights must sum to 100 but summed to " + sum);
    }
  }

  public int weightOf(RuleId rule) {
    return weights.get(rule);
  }

  /**
   * Technical capability decides whether the company can deliver at all; geography and budget only
   * decide whether it is worth pursuing. Keyword and segment together are 60, so capability
   * dominates, and no single rule can clear a 50 threshold alone.
   *
   * <p>State is already a profile criterion, so weighting it heavily double counts. If out-of-state
   * noise ever becomes a problem, the correct fix is to make state a filter, not to raise its
   * weight above 20.
   */
  public static WeightingScheme standard() {
    Map<RuleId, Integer> weights = new EnumMap<>(RuleId.class);
    weights.put(RuleId.KEYWORD, 30);
    weights.put(RuleId.SEGMENT, 30);
    weights.put(RuleId.ESTIMATED_VALUE, 15);
    weights.put(RuleId.STATE, 15);
    weights.put(RuleId.PROPOSAL_DEADLINE, 10);
    return new WeightingScheme(weights);
  }
}
