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
 * <p>{@code save} is an upsert keyed on the PNCP control number. Whether a save should happen at
 * all is not this interface's business: that is {@code ProcurementIngestion}, and it is a rule
 * rather than plumbing.
 */
public interface ProcurementRepository {

  Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber);

  void save(Procurement procurement);
}
