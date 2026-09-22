package io.github.furlanettoeduardo.radar.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the query service. This service owns the PostgreSQL schema through Flyway. */
@SpringBootApplication
public class RadarApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(RadarApiApplication.class, args);
  }
}
