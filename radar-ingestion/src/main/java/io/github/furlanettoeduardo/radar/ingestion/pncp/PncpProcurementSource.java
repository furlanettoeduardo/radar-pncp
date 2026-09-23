package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementSource;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The PNCP side of {@link ProcurementSource}.
 *
 * <p>Fetching happens in two phases, because the number of pages is only knowable after the first
 * response. Phase one asks for page one of every state and modality combination; phase two asks for
 * everything the {@code totalPaginas} of those answers revealed.
 *
 * <p>Both phases check the fan out cap <em>before</em> submitting anything, so the cap never fires
 * with work in flight. That matters: an exception that leaves virtual threads running would be
 * worse than the truncation the cap exists to avoid.
 *
 * <p>PNCP takes a single {@code uf}, so several states mean several requests, and a query covering
 * every state leaves the parameter off entirely. Modalities come from configuration rather than
 * from the query, because which modalities PNCP splits its catalogue into is a PNCP fact.
 */
@Component
public final class PncpProcurementSource implements ProcurementSource, ProcurementFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(PncpProcurementSource.class);
  private static final String REJECTED_COUNTER = "radar.pncp.notices.rejected";
  private static final String NOT_BIDDABLE_COUNTER = "radar.pncp.notices.not_biddable";

  private final PncpPageClient pageClient;
  private final PncpProcurementMapper mapper;
  private final PncpProperties properties;
  private final MeterRegistry meters;
  private final StructuredFanOut fanOut;

  public PncpProcurementSource(
      PncpPageClient pageClient,
      PncpProcurementMapper mapper,
      PncpProperties properties,
      MeterRegistry meters) {
    this.pageClient = Objects.requireNonNull(pageClient, "a source needs a page client");
    this.mapper = Objects.requireNonNull(mapper, "a source needs a mapper");
    this.properties = Objects.requireNonNull(properties, "a source needs its properties");
    this.meters = Objects.requireNonNull(meters, "a source needs somewhere to count rejections");
    this.fanOut =
        new StructuredFanOut(properties.maxConcurrentRequests(), properties.operationDeadline());
  }

  @Override
  public List<Procurement> find(ProcurementQuery query) {
    return fetch(query).stream().map(FetchedProcurement::procurement).toList();
  }

  /**
   * The richer view, for the ingestion pipeline: the raw payload and the change detector stay on
   * this side of the port, because they are storage and scheduling concerns rather than domain
   * data.
   */
  @Override
  public List<FetchedProcurement> fetch(ProcurementQuery query) {
    // One budget for the whole fetch, opened here and spent across both phases. Opening it inside
    // each phase would bound a run at twice the configured deadline rather than at it.
    StructuredFanOut.Budget budget = fanOut.startBudget();

    List<PncpPageRequest> firstPages = firstPageOfEachCombination(query);
    requireWithinCap(firstPages.size(), query);
    List<PncpPage> opening = fanOut.runAll(firstPages, pageClient::fetch, budget);

    List<PncpPageRequest> remaining = remainingPages(firstPages, opening);
    requireWithinCap(firstPages.size() + remaining.size(), query);
    List<PncpPage> rest = fanOut.runAll(remaining, pageClient::fetch, budget);

    List<FetchedProcurement> fetched = new ArrayList<>();
    opening.forEach(page -> map(page, fetched));
    rest.forEach(page -> map(page, fetched));
    return List.copyOf(fetched);
  }

  /**
   * One chunk: a single publication date, modality and state, paginated to completion.
   *
   * <p>Still two phases, because PNCP only reveals the page count in the first answer, but both now
   * spend one budget that belongs to the whole run rather than to this chunk. The cap applies to
   * this chunk alone, which is what stops a modality whose volume was underestimated from
   * truncating everything else in the run.
   */
  @Override
  public ChunkFetch fetchChunk(
      LocalDate publicationDate, int modalityCode, BrazilianState state, Instant deadline) {
    StructuredFanOut.Budget budget = new StructuredFanOut.Budget(deadline);
    PncpPageRequest first =
        new PncpPageRequest(publicationDate, publicationDate, Optional.of(state), modalityCode, 1);

    List<PncpPage> opening = fanOut.runAll(List.of(first), pageClient::fetch, budget);
    int totalPages = opening.get(0).totalPages();
    requireChunkWithinCap(totalPages, publicationDate, modalityCode, state);

    List<PncpPageRequest> remaining = new ArrayList<>();
    for (int page = 2; page <= totalPages; page++) {
      remaining.add(
          new PncpPageRequest(
              publicationDate, publicationDate, Optional.of(state), modalityCode, page));
    }
    List<PncpPage> rest = fanOut.runAll(remaining, pageClient::fetch, budget);

    List<FetchedProcurement> fetched = new ArrayList<>();
    opening.forEach(page -> map(page, fetched));
    rest.forEach(page -> map(page, fetched));
    return new ChunkFetch(List.copyOf(fetched), 1 + remaining.size());
  }

  private void requireChunkWithinCap(
      int pages, LocalDate publicationDate, int modalityCode, BrazilianState state) {
    if (pages > properties.maxPagesPerChunk()) {
      throw new PncpFanOutTooLargeException(
          ("%s modality %d in %s needs %d pages, over the per-chunk maximum of %d. That modality "
                  + "is busier than it was measured to be; re-measure it rather than raising the "
                  + "cap.")
              .formatted(
                  publicationDate, modalityCode, state, pages, properties.maxPagesPerChunk()));
    }
  }

  private List<PncpPageRequest> firstPageOfEachCombination(ProcurementQuery query) {
    List<Optional<BrazilianState>> states =
        query.coversEveryState()
            ? List.of(Optional.empty())
            : query.states().stream().map(Optional::of).toList();

    List<PncpPageRequest> requests = new ArrayList<>();
    for (Optional<BrazilianState> state : states) {
      for (int modality : properties.modalityCodes()) {
        requests.add(
            new PncpPageRequest(query.publishedFrom(), query.publishedTo(), state, modality, 1));
      }
    }
    return List.copyOf(requests);
  }

  private static List<PncpPageRequest> remainingPages(
      List<PncpPageRequest> firstPages, List<PncpPage> opening) {
    List<PncpPageRequest> requests = new ArrayList<>();
    for (int i = 0; i < firstPages.size(); i++) {
      PncpPageRequest first = firstPages.get(i);
      for (int page = 2; page <= opening.get(i).totalPages(); page++) {
        requests.add(
            new PncpPageRequest(
                first.publishedFrom(),
                first.publishedTo(),
                first.state(),
                first.modalityCode(),
                page));
      }
    }
    return List.copyOf(requests);
  }

  private void requireWithinCap(int pages, ProcurementQuery query) {
    if (pages > properties.maxPagesPerChunk()) {
      throw new PncpFanOutTooLargeException(
          ("%s to %s would need %d pages, over the configured maximum of %d. "
                  + "Narrow the date range rather than raising the cap.")
              .formatted(
                  query.publishedFrom(),
                  query.publishedTo(),
                  pages,
                  properties.maxPagesPerChunk()));
    }
  }

  private void map(PncpPage page, List<FetchedProcurement> into) {
    if (page.isEmpty()) {
      // A 204, or a page PNCP simply had nothing for. Normal, and never an escalation.
      return;
    }

    int rejected = 0;
    for (JsonNode notice : page.notices()) {
      switch (mapper.map(notice)) {
        case MappingResult.Mapped mapped -> into.add(mapped.fetched());
        case MappingResult.NotBiddable skipped -> record(skipped);
        case MappingResult.Rejected rejection -> {
          rejected++;
          record(rejection, notice);
        }
      }
    }

    if (rejected == page.notices().size()) {
      // Only malformed notices count here. A page of dispensas with no proposal window is a normal
      // page, not a contract change, which is why NotBiddable is a separate outcome.
      // One bad notice is data. Every notice on a page is a contract change, and continuing would
      // quietly report an empty day.
      throw new PncpMalformedResponseException(
          "every one of the %d notices on page %d was rejected, which is a contract change rather than bad data"
              .formatted(page.notices().size(), page.pageNumber()));
    }
  }

  /**
   * Expected, so INFO. A dispensa with no proposal window is what a dispensa is, and 9 of the 10
   * recorded modality 8 notices look like this. Counted separately so that a rise here reads as
   * "PNCP published more dispensas" rather than as "something broke".
   */
  private void record(MappingResult.NotBiddable skipped) {
    meters.counter(NOT_BIDDABLE_COUNTER).increment();
    LOG.info(
        "PNCP notice not biddable: control={} reason={}",
        skipped.controlNumber(),
        skipped.reason());
  }

  private void record(MappingResult.Rejected rejection, JsonNode notice) {
    meters.counter(REJECTED_COUNTER, "field", rejection.field()).increment();

    if (MappingResult.Rejected.UNKNOWN_NOTICE.equals(rejection.controlNumber())) {
      // Nothing identifies this notice, so the payload is the only way to chase it. Truncated,
      // because an unbounded body in a log line is how a 1 GB box runs out of disk.
      LOG.warn(
          "PNCP notice rejected: field={} reason={} payload={}",
          rejection.field(),
          rejection.reason(),
          Payloads.quote(notice.toString()));
      return;
    }
    LOG.warn(
        "PNCP notice rejected: control={} field={} reason={}",
        rejection.controlNumber(),
        rejection.field(),
        rejection.reason());
  }
}
