package io.github.furlanettoeduardo.radar.domain.procurement;

import java.time.Instant;
import java.util.Optional;

/**
 * Answers one question: does this notice have a proposal window at all.
 *
 * <p>It exists in the domain rather than in the HTTP mapper because "a procurement nobody can bid
 * on is not an opportunity for our users" is a statement about the product, not about parsing. The
 * mapper calls it; it does not own it.
 *
 * <p>It deliberately does <b>not</b> read the clock. Whether a window is still open is the deadline
 * rule's job, which grades urgency and disqualifies what has closed. Folding the two together would
 * turn "we saw this too late" into "this was never an opportunity", and those deserve different
 * answers: one is our failure, the other is the notice's nature.
 *
 * <p>Evidence for why this is needed: in the recorded modality 8 sample, 9 of 10 notices carry
 * neither date, and they are the same 9 records both times. Rejecting them as malformed, which is
 * what the mapper did before this existed, would have silently discarded 90 percent of dispensas.
 */
public final class Biddability {

  private Biddability() {}

  public static BiddabilityAssessment assess(
      Optional<Instant> proposalOpensAt, Optional<Instant> proposalClosesAt) {

    if (proposalOpensAt.isEmpty() && proposalClosesAt.isEmpty()) {
      return new BiddabilityAssessment.NotBiddable(
          "no proposal window: PNCP published neither an opening nor a closing date");
    }
    if (proposalClosesAt.isEmpty()) {
      return new BiddabilityAssessment.Malformed(
          "half a proposal window: dataAberturaProposta is present but dataEncerramentoProposta is"
              + " not");
    }
    if (proposalOpensAt.isEmpty()) {
      return new BiddabilityAssessment.Malformed(
          "half a proposal window: dataEncerramentoProposta is present but dataAberturaProposta is"
              + " not");
    }

    Instant opensAt = proposalOpensAt.get();
    Instant closesAt = proposalClosesAt.get();
    if (closesAt.isBefore(opensAt)) {
      return new BiddabilityAssessment.Malformed(
          "the proposal window closes before it opens: %s then %s".formatted(opensAt, closesAt));
    }
    return new BiddabilityAssessment.Biddable(opensAt, closesAt);
  }
}
