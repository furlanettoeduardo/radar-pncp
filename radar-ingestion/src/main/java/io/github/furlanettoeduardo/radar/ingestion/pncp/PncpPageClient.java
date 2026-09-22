package io.github.furlanettoeduardo.radar.ingestion.pncp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Fetches exactly one page from PNCP, with every timeout explicit and every failure classified.
 *
 * <p>Three outcomes are told apart because they deserve different treatment. A 5xx, a timeout or an
 * unreachable host is transient and is retried with exponential backoff and jitter. A 4xx is a
 * refusal and is never retried: asking again identically will be refused identically. A body this
 * client cannot read is not retried either, because a contract change will be just as unreadable
 * the second time and retrying would turn a loud failure into a slow one.
 *
 * <p>A 204 is an empty page, not a failure. PNCP answers that way for a query matching nothing, as
 * the recorded {@code caso-vazio} sample shows, and treating it as an error would make an ordinary
 * quiet day look like an outage.
 *
 * <p>Resilience4j is applied programmatically rather than through its annotations. The annotations
 * work through Spring AOP proxies, and these calls are made from inside virtual thread subtasks
 * that never pass through one: the annotations would have silently done nothing. The circuit
 * breaker sits inside the retry, so each attempt is a call it can measure.
 */
@Component
public final class PncpPageClient {

  private static final String PUBLICATION_PATH = "/v1/contratacoes/publicacao";
  private static final DateTimeFormatter PNCP_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

  private final PncpProperties properties;
  private final RestClient restClient;
  private final Retry retry;
  private final CircuitBreaker circuitBreaker;

  public PncpPageClient(PncpProperties properties) {
    this.properties = Objects.requireNonNull(properties, "the PNCP client needs its properties");

    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());

    this.restClient =
        RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(properties.baseUrl())
            .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
            .build();

    this.retry =
        Retry.of(
            "pncp",
            RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(
                    IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2.0, 0.5))
                .retryExceptions(PncpUnavailableException.class)
                .build());

    this.circuitBreaker =
        CircuitBreaker.of(
            "pncp",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(PncpUnavailableException.class)
                .ignoreExceptions(
                    PncpRequestRejectedException.class, PncpMalformedResponseException.class)
                .build());
  }

  public PncpPage fetch(PncpPageRequest request) {
    Supplier<PncpPage> once = () -> fetchOnce(request);
    return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, once))
        .get();
  }

  private PncpPage fetchOnce(PncpPageRequest request) {
    ResponseEntity<String> response;
    try {
      response =
          restClient
              .get()
              .uri(
                  builder -> {
                    builder
                        .path(PUBLICATION_PATH)
                        .queryParam("dataInicial", request.publishedFrom().format(PNCP_DATE))
                        .queryParam("dataFinal", request.publishedTo().format(PNCP_DATE))
                        .queryParam("codigoModalidadeContratacao", request.modalityCode())
                        .queryParam("pagina", request.page())
                        .queryParam("tamanhoPagina", properties.pageSize());
                    request.state().ifPresent(state -> builder.queryParam("uf", state.name()));
                    return builder.build();
                  })
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (ignored, refused) -> {
                    throw new PncpRequestRejectedException(
                        "PNCP refused the request with %d: %s"
                            .formatted(refused.getStatusCode().value(), quote(refused.getBody())));
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (ignored, failed) -> {
                    throw new PncpUnavailableException(
                        "PNCP answered %d: %s"
                            .formatted(failed.getStatusCode().value(), quote(failed.getBody())));
                  })
              .toEntity(String.class);
    } catch (ResourceAccessException unreachable) {
      throw new PncpUnavailableException(
          "could not reach PNCP: " + unreachable.getMessage(), unreachable);
    }

    String body = response.getBody();
    if (body == null || body.isBlank()) {
      return PncpPage.empty(request.page());
    }
    return parse(body, request.page());
  }

  private static PncpPage parse(String body, int requestedPage) {
    JsonNode envelope;
    try {
      envelope = PncpJson.mapper().readTree(body);
    } catch (JsonProcessingException unreadable) {
      throw new PncpMalformedResponseException(
          "PNCP answered with something that is not JSON: " + quote(body), unreadable);
    }

    JsonNode data = envelope.get("data");
    if (data == null || !data.isArray()) {
      throw new PncpMalformedResponseException(
          "PNCP answered without a data array: " + quote(body));
    }

    List<JsonNode> notices = new ArrayList<>(data.size());
    data.forEach(notices::add);
    return new PncpPage(
        notices,
        envelope.path("totalRegistros").asInt(notices.size()),
        envelope.path("totalPaginas").asInt(1),
        envelope.path("numeroPagina").asInt(requestedPage));
  }

  private static String quote(String body) {
    return Payloads.quote(body);
  }

  private static String quote(InputStream body) {
    try {
      return quote(new String(body.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException unreadable) {
      return "<error body could not be read: " + unreadable.getMessage() + ">";
    }
  }
}
