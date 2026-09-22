package io.github.furlanettoeduardo.radar.ingestion.pncp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the recorded PNCP responses in docs/samples, rather than keeping a second copy of them in
 * test resources that would drift from the first.
 */
public final class SampleFixtures {

  private static final Path SAMPLES = Path.of("..", "docs", "samples");

  private SampleFixtures() {}

  public static String read(String fileName) {
    Path sample = SAMPLES.resolve(fileName);
    if (!Files.isRegularFile(sample)) {
      throw new IllegalStateException(
          "recorded sample not found at " + sample.toAbsolutePath().normalize());
    }
    try {
      return Files.readString(sample, StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException("could not read recorded sample " + fileName, cause);
    }
  }
}
