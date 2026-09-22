package io.github.furlanettoeduardo.radar.ingestion;

/**
 * Fails fast, and legibly, when the JVM was started without {@code --enable-preview}.
 *
 * <p>Without this the symptom is an {@code UnsupportedClassVersionError} wrapped in a {@code
 * BeanCreationException}, surfacing thirty frames deep while a bean graph is being built, naming a
 * class nobody was thinking about. The cause is one missing word on a command line.
 *
 * <p>The check loads the one preview compiled class deliberately, before Spring starts, rather than
 * inspecting JVM arguments. Reading {@code getInputArguments} would test a proxy for the condition;
 * this tests the condition.
 */
public final class PreviewFeatures {

  /** The only class in this project compiled against a preview API. */
  private static final String PREVIEW_COMPILED_CLASS =
      "io.github.furlanettoeduardo.radar.ingestion.pncp.StructuredFanOut";

  private PreviewFeatures() {}

  public static void requireEnabled() {
    try {
      Class.forName(PREVIEW_COMPILED_CLASS, false, PreviewFeatures.class.getClassLoader());
    } catch (UnsupportedClassVersionError notEnabled) {
      throw new IllegalStateException(
          """
          radar-ingestion was started without --enable-preview.

          StructuredTaskScope is a preview API in Java 21, so %s cannot be loaded without it and \
          this service cannot start. The flag is required in three places: the maven-compiler-plugin \
          and the test JVMs in radar-ingestion/pom.xml, and the ENTRYPOINT in \
          radar-ingestion/Dockerfile.

          It is deliberately NOT in JAVA_TOOL_OPTIONS, because docker compose and Kubernetes replace \
          that variable wholesale and would strip it. If you are running the jar by hand, use:
            java --enable-preview -jar radar-ingestion.jar

          See docs/adr/0008-virtual-threads-and-structured-concurrency-for-page-fetching.md\
          """
              .formatted(PREVIEW_COMPILED_CLASS),
          notEnabled);
    } catch (ClassNotFoundException missing) {
      throw new IllegalStateException(
          "expected " + PREVIEW_COMPILED_CLASS + " on the classpath but it is absent", missing);
    }
  }
}
