package io.github.furlanettoeduardo.radar.domain.port;

import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake, not a mock, and thread safe on purpose: the conditional writes have to behave like real
 * compare-and-set or the contention tests that use it would prove nothing.
 *
 * <p>Held to {@link ProcurementRepositoryContract} alongside the JDBC adapter, which is the only
 * reason it is safe for domain tests to trust it. A fake nobody checks against the real thing is a
 * second implementation of the same contract, free to be wrong in its own direction.
 */
public final class InMemoryProcurementRepository implements ProcurementRepository {

  private final ConcurrentHashMap<PncpControlNumber, Procurement> rows = new ConcurrentHashMap<>();
  private final AtomicInteger writes = new AtomicInteger();
  private final AtomicBoolean rejectEverything = new AtomicBoolean();
  private volatile Runnable beforeNextWrite;

  public int writes() {
    return writes.get();
  }

  /** Makes every conditional write report that it lost, however many times it is retried. */
  public void rejectEveryConditionalWrite() {
    rejectEverything.set(true);
  }

  /** Simulates another writer slipping in between our read and our conditional write. */
  public void beforeNextWrite(Runnable interference) {
    this.beforeNextWrite = interference;
  }

  private void runInterference() {
    Runnable once = beforeNextWrite;
    if (once != null) {
      beforeNextWrite = null;
      once.run();
    }
  }

  @Override
  public Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber) {
    return Optional.ofNullable(rows.get(controlNumber));
  }

  @Override
  public boolean insertIfAbsent(Procurement procurement) {
    runInterference();
    if (rejectEverything.get()) {
      return false;
    }
    boolean inserted = rows.putIfAbsent(procurement.controlNumber(), procurement) == null;
    if (inserted) {
      writes.incrementAndGet();
    }
    return inserted;
  }

  @Override
  public boolean replaceIfUnchanged(Procurement procurement, String expectedSourcePayloadHash) {
    runInterference();
    if (rejectEverything.get()) {
      return false;
    }
    AtomicBoolean replaced = new AtomicBoolean();
    rows.computeIfPresent(
        procurement.controlNumber(),
        (key, current) -> {
          if (current.sourcePayloadHash().equals(expectedSourcePayloadHash)) {
            replaced.set(true);
            return procurement;
          }
          return current;
        });
    if (replaced.get()) {
      writes.incrementAndGet();
    }
    return replaced.get();
  }
}
