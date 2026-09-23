package io.github.furlanettoeduardo.radar.domain.port;

import org.junit.jupiter.api.DisplayName;

/**
 * The fake, held to the same contract as the database adapter.
 *
 * <p>Without this the fake is just a convenient object that happens to compile, and every domain
 * test built on it proves something about a map rather than about the system.
 */
@DisplayName("in-memory ProcurementRepository")
class InMemoryProcurementRepositoryTest extends ProcurementRepositoryContract {

  @Override
  protected ProcurementRepository repository() {
    return new InMemoryProcurementRepository();
  }
}
