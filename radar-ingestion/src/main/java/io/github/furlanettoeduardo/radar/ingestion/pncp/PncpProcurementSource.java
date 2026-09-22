package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementQuery;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementSource;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import io.micrometer.core.instrument.MeterRegistry;
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
public final class PncpProcurementSource implements ProcurementSource {

  private static final Logger LOG = LoggerFactory.getLogger(PncpProcurementSource.class);
  private static final String REJECTED_COUNTER = "radar.pncp.notices.rejected";

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
    this.fanOut = new StructuredFanOut(properties.maxConcurrentRequests());
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
  public List<FetchedProcurement> fetch(ProcurementQuery query) {
    List<PncpPageRequest> firstPages = firstPageOfEachCombination(query);
    requireWithinCap(firstPages.size(), query);
    List<PncpPage> opening = fanOut.runAll(firstPages, pageClient::fetch);

    List<PncpPageRequest> remaining = remainingPages(firstPages, opening);
    requireWithinCap(firstPages.size() + remaining.size(), query);
    List<PncpPage> rest = fanOut.runAll(remaining, pageClient::fetch);

    List<FetchedProcurement> fetched = new ArrayList<>();
    opening.forEach(page -> map(page, fetched));
    rest.forEach(page -> map(page, fetched));
    return List.copyOf(fetched);
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
    if (pages > properties.maxTotalPages()) {
      throw new PncpFanOutTooLargeException(
          ("%s to %s would need %d pages, over the configured maximum of %d. "
                  + "Narrow the date range rather than raising the cap.")
              .formatted(
                  query.publishedFrom(), query.publishedTo(), pages, properties.maxTotalPages()));
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
        case MappingResult.Rejected rejection -> {
          rejected++;
          record(rejection, notice);
        }
      }
    }

    if (rejected == page.notices().size()) {
      // One bad notice is data. Every notice on a page is a contract change, and continuing would
      // quietly report an empty day.
      throw new PncpMalformedResponseException(
          "every one of the %d notices on page %d was rejected, which is a contract change rather than bad data"
              .formatted(page.notices().size(), page.pageNumber()));
    }
  }

  private void record(MappingResult.Rejected rejection, JsonNode notice) {
    meters.counter(REJECTED_COUNTER, "field", rejection.field()).increment();

    if (MappingResult.Rejected.UNKNOWN_NOTICE.equals(rejection.controlNumber())) {
      // Nothing identifies this notice, so the payload is the only way to chase it. Truncated,
      // because an unbounded body in a log line is how a 1 GB box runs out of disk.
      LOG.error(
          "PNCP notice rejected: field={} reason={} payload={}",
          rejection.field(),
          rejection.reason(),
          Payloads.quote(notice.toString()));
      return;
    }
    LOG.error(
        "PNCP notice rejected: control={} field={} reason={}",
        rejection.controlNumber(),
        rejection.field(),
        rejection.reason());
  }
}
