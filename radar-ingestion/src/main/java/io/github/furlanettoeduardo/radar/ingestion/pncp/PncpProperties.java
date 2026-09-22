package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about talking to PNCP that should be changeable without a release.
 *
 * <p>{@code modalityCodes} is a list from the outset even though it holds one value. PNCP requires
 * {@code codigoModalidadeContratacao} on every call and the recorded samples only ever use 6, so
 * that is all this system polls. Which modalities PNCP splits its catalogue into is a PNCP fact
 * rather than a domain concept, which is why it lives here and not in a query.
 *
 * <p>{@code maxTotalPages} caps the whole fan-out, not just how much of it runs at once. A wide
 * date range across every state is thousands of requests against a public API run by a public body.
 *
 * <p>{@code operationDeadline} bounds a whole invocation. Per request timeouts bound one call; at
 * the cap the arithmetic is unkind, and a degraded PNCP could otherwise keep one run alive for over
 * half an hour. Once a scheduler triggers this periodically, that is overlapping runs.
 *
 * <p>{@code userAgent} identifies this project and links to its repository. It is a public service;
 * being identifiable costs nothing and is the courteous default.
 */
@ConfigurationProperties(prefix = "radar.pncp")
public record PncpProperties(
    String baseUrl,
    List<Integer> modalityCodes,
    int pageSize,
    int maxTotalPages,
    int maxConcurrentRequests,
    Duration connectTimeout,
    Duration readTimeout,
    Duration operationDeadline,
    String userAgent) {

  public PncpProperties {
    Objects.requireNonNull(baseUrl, "radar.pncp.base-url is required");
    Objects.requireNonNull(modalityCodes, "radar.pncp.modality-codes is required");
    Objects.requireNonNull(connectTimeout, "radar.pncp.connect-timeout is required");
    Objects.requireNonNull(readTimeout, "radar.pncp.read-timeout is required");
    Objects.requireNonNull(operationDeadline, "radar.pncp.operation-deadline is required");
    Objects.requireNonNull(userAgent, "radar.pncp.user-agent is required");
    if (modalityCodes.isEmpty()) {
      throw new IllegalArgumentException(
          "at least one modality code is required: PNCP rejects the call without one");
    }
    modalityCodes = List.copyOf(modalityCodes);
    requirePositive(pageSize, "page-size");
    requirePositive(maxTotalPages, "max-total-pages");
    requirePositive(maxConcurrentRequests, "max-concurrent-requests");
    if (connectTimeout.isNegative() || connectTimeout.isZero()) {
      throw new IllegalArgumentException("radar.pncp.connect-timeout must be positive");
    }
    if (readTimeout.isNegative() || readTimeout.isZero()) {
      throw new IllegalArgumentException("radar.pncp.read-timeout must be positive");
    }
    if (operationDeadline.compareTo(readTimeout) <= 0) {
      throw new IllegalArgumentException(
          "radar.pncp.operation-deadline must exceed the read timeout, or no request can finish");
    }
    if (userAgent.isBlank()) {
      throw new IllegalArgumentException("radar.pncp.user-agent must identify this client");
    }
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(
          "radar.pncp.%s must be positive but was %d".formatted(name, value));
    }
  }
}
