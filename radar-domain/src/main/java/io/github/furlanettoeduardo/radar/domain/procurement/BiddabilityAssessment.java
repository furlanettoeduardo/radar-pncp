package io.github.furlanettoeduardo.radar.domain.procurement;

import java.time.Instant;
import java.util.Objects;

/**
 * What a notice's proposal window says about whether it can be bid on.
 *
 * <p>Three outcomes, and the third is the reason this is sealed rather than a boolean. A notice
 * with no window is a business outcome: that is simply what a dispensa is, and it is not an error.
 * A notice with half a window is bad data. Collapsing those two would hide a contract change behind
 * a routine counter, and the counter would keep ticking while nobody looked.
 */
public sealed interface BiddabilityAssessment {

  /** A window exists. Whether it is still open is the deadline rule's business, not this one's. */
  record Biddable(Instant opensAt, Instant closesAt) implements BiddabilityAssessment {

    public Biddable {
      Objects.requireNonNull(opensAt, "a biddable window must have an opening");
      Objects.requireNonNull(closesAt, "a biddable window must have a closing");
    }
  }

  /** No window at all, so nobody can bid. Expected, countable, not an error. */
  record NotBiddable(String reason) implements BiddabilityAssessment {

    public NotBiddable {
      Objects.requireNonNull(reason, "a not biddable outcome must carry a reason");
    }
  }

  /** The window is inconsistent. Bad data, and worth a louder log than a routine outcome. */
  record Malformed(String reason) implements BiddabilityAssessment {

    public Malformed {
      Objects.requireNonNull(reason, "a malformed outcome must carry a reason");
    }
  }
}
