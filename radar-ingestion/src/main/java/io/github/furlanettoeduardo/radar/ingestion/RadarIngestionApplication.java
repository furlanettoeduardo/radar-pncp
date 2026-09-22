package io.github.furlanettoeduardo.radar.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the ingestion service: PNCP polling, enrichment and SQS traffic. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RadarIngestionApplication {

  public static void main(String[] args) {
    SpringApplication.run(RadarIngestionApplication.class, args);
  }
}
