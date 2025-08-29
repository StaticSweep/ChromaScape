package com.chromascape.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The main entry point for the ChromaScape Spring Boot application.
 *
 * <p>This class bootstraps the entire backend system, initializing all Spring components such as
 * REST controllers, services, and configuration classes.</p>
 */
@SpringBootApplication
public class ChromaScapeApplication {

  /**
   * Launches the ChromaScape application.
   *
   * @param args command-line arguments passed to the application
   */
  public static void main(String[] args) {
    SpringApplication.run(ChromaScapeApplication.class, args);
  }
}
