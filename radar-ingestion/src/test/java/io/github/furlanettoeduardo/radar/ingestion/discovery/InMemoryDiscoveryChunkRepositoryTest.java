package io.github.furlanettoeduardo.radar.ingestion.discovery;

import org.junit.jupiter.api.DisplayName;

/** The fake, held to the same contract as the PostgreSQL adapter. */
@DisplayName("in-memory DiscoveryChunkRepository")
class InMemoryDiscoveryChunkRepositoryTest extends DiscoveryChunkRepositoryContract {

  @Override
  protected DiscoveryChunkRepository repository() {
    return new InMemoryDiscoveryChunkRepository();
  }
}
