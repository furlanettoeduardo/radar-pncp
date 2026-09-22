package io.github.furlanettoeduardo.radar.domain.procurement;

import java.time.Instant;
import java.util.Objects;

/**
 * What became of a discovered procurement.
 *
 * <p>Three outcomes rather than a boolean, because "we did not store it" has two meanings that need
 * telling apart when something looks wrong: the content was identical, or the message was older
 * than what we already hold. The first is the system working; the second is worth counting.
 */
public sealed interface IngestionOutcome {

  /** New, or genuinely changed and newer. */
  record Stored(Procurement procurement) implements IngestionOutcome {

    public Stored {
      Objects.requireNonNull(procurement, "a stored outcome must carry what was stored");
    }
  }

  /** Identical content. A duplicate delivery, which SQS guarantees will happen. */
  record Unchanged(PncpControlNumber controlNumber) implements IngestionOutcome {

    public Unchanged {
      Objects.requireNonNull(controlNumber, "an unchanged outcome must name the procurement");
    }
  }

  /** Different content, but PNCP says it is older than what is already stored. */
  record Stale(Instant storedSourceUpdatedAt, Instant incomingSourceUpdatedAt)
      implements IngestionOutcome {

    public Stale {
      Objects.requireNonNull(storedSourceUpdatedAt, "a stale outcome must carry both timestamps");
      Objects.requireNonNull(incomingSourceUpdatedAt, "a stale outcome must carry both timestamps");
    }
  }
}
