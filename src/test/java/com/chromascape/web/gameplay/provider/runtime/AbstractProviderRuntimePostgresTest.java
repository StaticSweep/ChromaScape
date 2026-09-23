package com.chromascape.web.gameplay.provider.runtime;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Isolated PostgreSQL harness. Ports default to the SC-1430 review database (55450) and can be
 * pointed anywhere with the chromascape.sc1430.jdbc-* system properties.
 */
@SpringBootTest(
    classes = ProviderRuntimePostgresTestApplication.class,
    properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
abstract class AbstractProviderRuntimePostgresTest {
  private static final String DEFAULT_JDBC_URL =
      "jdbc:postgresql://127.0.0.1:55450/chromascape_sc1430_review?sslmode=disable";

  @DynamicPropertySource
  static void isolatedDatasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> System.getProperty("chromascape.sc1430.jdbc-url", DEFAULT_JDBC_URL));
    registry.add(
        "spring.datasource.username",
        () -> System.getProperty("chromascape.sc1430.jdbc-username", "chromascape"));
    registry.add(
        "spring.datasource.password",
        () -> System.getProperty("chromascape.sc1430.jdbc-password", "chromascape123"));
  }
}
