package io.github.furlanettoeduardo.radar.domain.feedback;

import java.util.Objects;
import java.util.UUID;

/** Identity of a Feedback aggregate. */
public record FeedbackId(UUID value) {

  public FeedbackId {
    Objects.requireNonNull(value, "a feedback id must have a value");
  }
}
