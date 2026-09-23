package io.github.furlanettoeduardo.radar.ingestion.pncp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * A manual smoke test against the real PNCP API. <b>Never runs in CI.</b>
 *
 * <p>Run it with:
 *
 * <pre>
 *   mvn -pl radar-ingestion -am test -Dtest=PncpLiveSmokeTest -Dpncp.live=true
 * </pre>
 *
 * <p>Optionally pin the window, which otherwise defaults to the last seven days:
 *
 * <pre>
 *   -Dpncp.live.from=2026-09-15 -Dpncp.live.to=2026-09-21
 * </pre>
 *
 * <p>It is gated by {@code @EnabledIfSystemProperty} rather than {@code @Disabled} for one
 * practical reason: a {@code @Disabled} test cannot be switched on from a command line, so there
 * would be no command to give. The effect is the same — it is off unless somebody asks for it by
 * name — and unlike {@code @Disabled} it is actually runnable.
 *
 * <p>It fetches <b>one page</b>, not the whole result set. A week of SP is 170 pages, and a smoke
 * test has no business making 170 requests against a public API run by a public body. One page
 * exercises the client, the mapper and the time zone conversion, which is everything this is for.
 *
 * <p>This is a diagnostic, not a unit test. It asserts only what must hold for its own output to be
 * meaningful; its value is in what it prints.
 *
 * <h2>When this fails, PNCP may be down rather than the adapter broken</h2>
 *
 * <p>This was not hypothetical on 2026-09-22: the same query that had worked an hour earlier began
 * failing, and PNCP was returning 504 under load. Before reading the failure as a regression, check
 * from outside the JVM.
 *
 * <p><b>Is PNCP answering at all?</b> No client timeout, so its gateway has time to say what it
 * actually thinks:
 *
 * <pre>
 *   curl -sS -o /dev/null -w '%{http_code} in %{time_total}s
 * '  *     'https://pncp.gov.br/api/consulta/v1/contratacoes/publicacao?dataInicial=20260915&amp;dataFinal=20260922&amp;codigoModalidadeContratacao=6&amp;uf=SP&amp;pagina=1&amp;tamanhoPagina=10'
 *
 *   # PowerShell
 *   Invoke-WebRequest -Uri '...same URL...' -TimeoutSec 120
 * </pre>
 *
 * <p>A <b>502, 503 or 504</b> is PNCP, not us. A <b>200</b> means the adapter is the suspect.
 *
 * <p><b>Is the host reachable?</b> Separates an upstream outage from a local network or DNS
 * problem:
 *
 * <pre>
 *   Test-NetConnection pncp.gov.br -Port 443      # PowerShell
 *   nc -vz pncp.gov.br 443                        # or: openssl s_client -connect pncp.gov.br:443
 * </pre>
 *
 * <p>TCP connecting while HTTP returns 5xx is the signature of a degraded origin behind a healthy
 * gateway. TCP failing points at DNS, a proxy or the local network instead.
 *
 * <p><b>One arithmetic check worth doing.</b> If this test fails after roughly {@code 3 x
 * read-timeout}, the client gave up before PNCP answered, so no HTTP status was ever received and
 * the failure is a read timeout rather than the 504 a browser or curl would show. Those are
 * different failures with the same cause, and only the external check above can tell them apart.
 */
@EnabledIfSystemProperty(
    named = "pncp.live",
    matches = "true",
    disabledReason = "hits the real PNCP API; run manually with -Dpncp.live=true")
class PncpLiveSmokeTest {

  private static final String LIVE_BASE_URL = "https://pncp.gov.br/api/consulta";

  @Test
  @DisplayName("fetches one live page from PNCP and reports what the adapter made of it")
  void fetchesOneLivePage() {
    LocalDate from =
        LocalDate.parse(
            System.getProperty("pncp.live.from", LocalDate.now().minusDays(7).toString()));
    LocalDate to = LocalDate.parse(System.getProperty("pncp.live.to", LocalDate.now().toString()));

    PncpProperties properties =
        new PncpProperties(
            LIVE_BASE_URL,
            List.of(6),
            10,
            500,
            8,
            Duration.ofSeconds(5),
            Duration.ofSeconds(20),
            Duration.ofMinutes(2),
            "radar-pncp/0.1.0 (+https://github.com/furlanettoeduardo/radar-pncp)");

    PncpPage page =
        new PncpPageClient(properties)
            .fetch(new PncpPageRequest(from, to, Optional.of(BrazilianState.SP), 6, 1));

    PncpProcurementMapper mapper = new PncpProcurementMapper();
    List<Procurement> mapped = new ArrayList<>();
    List<MappingResult.NotBiddable> notBiddable = new ArrayList<>();
    List<MappingResult.Rejected> rejected = new ArrayList<>();
    for (JsonNode notice : page.notices()) {
      switch (mapper.map(notice)) {
        case MappingResult.Mapped ok -> mapped.add(ok.fetched().procurement());
        case MappingResult.NotBiddable skipped -> notBiddable.add(skipped);
        case MappingResult.Rejected no -> rejected.add(no);
      }
    }

    report(from, to, page, mapped, notBiddable, rejected);

    assertThat(mapped.size() + notBiddable.size() + rejected.size())
        .isEqualTo(page.notices().size());
  }

  private static void report(
      LocalDate from,
      LocalDate to,
      PncpPage page,
      List<Procurement> mapped,
      List<MappingResult.NotBiddable> notBiddable,
      List<MappingResult.Rejected> rejected) {

    StringBuilder out = new StringBuilder("\n");
    out.append("=== PNCP live smoke ===============================================\n");
    out.append("window            : %s to %s, uf=SP, modalidade=6%n".formatted(from, to));
    out.append("totalRegistros    : %d%n".formatted(page.totalRecords()));
    out.append("totalPaginas      : %d%n".formatted(page.totalPages()));
    out.append("records on page 1 : %d%n".formatted(page.notices().size()));
    out.append("mapped            : %d%n".formatted(mapped.size()));
    out.append("not biddable      : %d   (no proposal window)%n".formatted(notBiddable.size()));
    out.append("rejected          : %d%n".formatted(rejected.size()));

    rejected.forEach(
        no ->
            out.append(
                "  rejected: control=%s field=%s reason=%s%n"
                    .formatted(no.controlNumber(), no.field(), no.reason())));

    if (page.notices().isEmpty()) {
      out.append("\nPNCP returned no records for this window, so nothing to convert.\n");
    } else {
      JsonNode first = page.notices().get(0);
      out.append("\n--- first record, time zone check ---\n");
      out.append(
          "numeroControlePNCP       : %s%n".formatted(first.path("numeroControlePNCP").asText()));
      String rawClose = first.path("dataEncerramentoProposta").asText();
      out.append(
          "dataEncerramentoProposta : %s   (naive, as PNCP published it)%n".formatted(rawClose));

      if (!rawClose.isBlank()) {
        ZonedDateTime inSaoPaulo =
            PncpTimestamps.toInstant(rawClose).atZone(PncpTimestamps.PNCP_ZONE);
        out.append(
            "parsed as Instant        : %s   (UTC)%n"
                .formatted(PncpTimestamps.toInstant(rawClose)));
        out.append(
            "back in %s : %s   (offset %s)%n"
                .formatted(PncpTimestamps.PNCP_ZONE, inSaoPaulo, inSaoPaulo.getOffset()));
      }
    }
    out.append("===================================================================\n");
    System.out.println(out);
  }
}
