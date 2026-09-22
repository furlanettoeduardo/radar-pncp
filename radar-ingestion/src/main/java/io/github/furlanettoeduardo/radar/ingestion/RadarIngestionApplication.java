package io.github.furlanettoeduardo.radar.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the ingestion service: PNCP polling, enrichment and SQS traffic. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RadarIngestionApplication {

  public static void main(String[] args) {
    // Before Spring, so a missing preview flag reads as one sentence rather than as a
    // BeanCreationException thirty frames deep.
    PreviewFeatures.requireEnabled();
    SpringApplication.run(RadarIngestionApplication.class, args);
  }
}
