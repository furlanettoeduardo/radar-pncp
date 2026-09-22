package io.github.furlanettoeduardo.radar.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the wiring of {@code --enable-preview} against the drift that already happened once.
 *
 * <p>It was originally carried in {@code JAVA_TOOL_OPTIONS} in the Dockerfile. {@code
 * docker-compose.yml} also sets that variable for this service, compose replaces an image
 * environment variable rather than appending to it, and the flag silently vanished. The container
 * logged {@code Picked up JAVA_TOOL_OPTIONS: -Xmx256m} and the service failed to start.
 *
 * <p>These assertions are cheap and they fail at build time, which is the only moment this class of
 * mistake is cheap to fix.
 */
class PreviewFlagWiringTest {

  private static final String FLAG = "--enable-preview";

  @Test
  @DisplayName(
      "the flag is on the Dockerfile ENTRYPOINT, where no environment variable can strip it")
  void theFlagIsOnTheEntrypoint() {
    String entrypoint =
        read(Path.of("Dockerfile"))
            .lines()
            .filter(line -> line.startsWith("ENTRYPOINT"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("radar-ingestion/Dockerfile has no ENTRYPOINT"));

    assertThat(entrypoint).contains(FLAG);
  }

  @Test
  @DisplayName("the flag is not left to JAVA_TOOL_OPTIONS, which compose and Kubernetes replace")
  void theFlagIsNotLeftToAnEnvironmentVariable() {
    String dockerfile = read(Path.of("Dockerfile"));

    dockerfile
        .lines()
        .filter(line -> line.startsWith("ENV JAVA_TOOL_OPTIONS"))
        .forEach(
            line ->
                assertThat(line)
                    .as("JAVA_TOOL_OPTIONS is replaced wholesale by any orchestrator above it")
                    .doesNotContain(FLAG));
  }

  @Test
  @DisplayName("compose does not override the entrypoint for this service")
  void composeDoesNotOverrideTheEntrypoint() {
    String compose = read(Path.of("..", "docker-compose.yml"));
    String ingestionService =
        compose.substring(compose.indexOf("radar-ingestion:") + "radar-ingestion:".length());

    assertThat(ingestionService.lines().takeWhile(line -> !line.isBlank()))
        .as("an entrypoint or command override in compose would drop the preview flag")
        .noneMatch(
            line -> line.trim().startsWith("entrypoint:") || line.trim().startsWith("command:"));
  }

  @Test
  @DisplayName("the build carries the flag for the compiler and both test JVMs")
  void theBuildCarriesTheFlag() {
    String pom = read(Path.of("pom.xml"));

    assertThat(pom).contains("<arg>" + FLAG + "</arg>");
    assertThat(pom.split("<argLine>" + FLAG + "</argLine>", -1))
        .as("surefire and failsafe each need it")
        .hasSize(3);
  }

  @Test
  @DisplayName("the boot check passes on a JVM that has the flag, so the check itself works")
  void theBootCheckPassesWhenTheFlagIsPresent() {
    assertThatCode(PreviewFeatures::requireEnabled).doesNotThrowAnyException();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException("could not read " + path.toAbsolutePath().normalize(), cause);
    }
  }
}
