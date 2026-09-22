package io.github.furlanettoeduardo.radar.domain.feedback;

import io.github.furlanettoeduardo.radar.domain.matching.MatchId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a user said about a match.
 *
 * <p>Its own aggregate, referencing the match by identity. It arrives arbitrarily later than the
 * match, more than once, and from a person rather than from the scoring engine. Unlike a match, its
 * identity is generated rather than derived: each submission is an event in its own right, and a
 * user changing their mind is new information, not a correction.
 */
public record Feedback(FeedbackId id, MatchId match, Verdict verdict, Instant givenAt) {

  public Feedback {
    Objects.requireNonNull(id, "feedback must have an id");
    Objects.requireNonNull(match, "feedback must be about a match");
    Objects.requireNonNull(verdict, "feedback must carry a verdict");
    Objects.requireNonNull(givenAt, "feedback must record when it was given");
  }

  public static Feedback relevant(MatchId match, Instant givenAt) {
    return new Feedback(new FeedbackId(UUID.randomUUID()), match, Verdict.RELEVANT, givenAt);
  }

  public static Feedback notRelevant(MatchId match, Instant givenAt) {
    return new Feedback(new FeedbackId(UUID.randomUUID()), match, Verdict.NOT_RELEVANT, givenAt);
  }
}
