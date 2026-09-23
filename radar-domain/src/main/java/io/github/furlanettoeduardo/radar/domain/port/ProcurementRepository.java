package io.github.furlanettoeduardo.radar.domain.port;

import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Optional;

/**
 * Where discovered procurements are kept.
 *
 * <p>The other half of {@link ProcurementSource}. Both belong to the same flow, so both are ports:
 * having one as a port and the other as an adapter detail would put the whole ingestion decision
 * outside the domain, leaving the domain a spectator in its own use case.
 *
 * <p>Both writes are conditional, and <b>neither knows the rule.</b> They do not mention timestamps
 * and have no opinion about who should win; they promise only "write if nothing has changed since I
 * read". Deciding whether a write should happen belongs to {@code ProcurementIngestion}, and
 * keeping it there means there is exactly one copy of it. An implementation that encoded the rule
 * in SQL would be a second copy, free to drift from the first.
 *
 * <p>Comparing on the content hash alone is sufficient, because the hash is taken over the whole
 * PNCP payload and therefore covers {@code dataAtualizacaoGlobal} as well. Two states with the same
 * hash are the same state, so the classic ABA problem is benign here: the decision depends only on
 * what was read, and a value that departed and returned is indistinguishable from one that never
 * left.
 */
public interface ProcurementRepository {

  Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber);

  /**
   * @return true if this call created the row, false if somebody else already had.
   */
  boolean insertIfAbsent(Procurement procurement);

  /**
   * @return true if the stored row still had {@code expectedSourcePayloadHash} and was replaced.
   */
  boolean replaceIfUnchanged(Procurement procurement, String expectedSourcePayloadHash);
}
