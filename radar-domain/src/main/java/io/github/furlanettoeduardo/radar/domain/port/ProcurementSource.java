package io.github.furlanettoeduardo.radar.domain.port;

import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.List;

/**
 * Where procurements come from. Implemented by an adapter against the PNCP API; the domain knows
 * only that something can answer this question.
 */
public interface ProcurementSource {

  List<Procurement> find(ProcurementQuery query);
}
