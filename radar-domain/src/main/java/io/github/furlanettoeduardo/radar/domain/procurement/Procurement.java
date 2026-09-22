package io.github.furlanettoeduardo.radar.domain.procurement;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A procurement notice published on PNCP. */
public record Procurement(
    PncpControlNumber controlNumber,
    String objectDescription,
    BrazilianState state,
    Optional<MonetaryValue> estimatedValue,
    Modality modality,
    Instant publishedAt,
    Instant proposalOpensAt,
    Instant proposalClosesAt,
    String sourcePayloadHash) {

  public Procurement {
    Objects.requireNonNull(controlNumber, "a procurement must have a control number");
    Objects.requireNonNull(objectDescription, "a procurement must have an object description");
    Objects.requireNonNull(state, "a procurement must have a state");
    Objects.requireNonNull(
        estimatedValue, "estimated value must not be null, use Optional.empty() when secret");
    Objects.requireNonNull(modality, "a procurement must have a modality");
    Objects.requireNonNull(publishedAt, "a procurement must record when PNCP published it");
    Objects.requireNonNull(proposalOpensAt, "a procurement must have a proposal opening time");
    Objects.requireNonNull(proposalClosesAt, "a procurement must have a proposal closing time");
    Objects.requireNonNull(sourcePayloadHash, "a procurement must carry its source hash");
    if (proposalClosesAt.isBefore(proposalOpensAt)) {
      throw new IllegalArgumentException(
          "a proposal window cannot close before it opens: %s then %s"
              .formatted(proposalOpensAt, proposalClosesAt));
    }
    if (sourcePayloadHash.isBlank()) {
      throw new IllegalArgumentException("a procurement source hash must not be blank");
    }
    if (objectDescription.isBlank()) {
      throw new IllegalArgumentException("a procurement object description must not be blank");
    }
  }
}
