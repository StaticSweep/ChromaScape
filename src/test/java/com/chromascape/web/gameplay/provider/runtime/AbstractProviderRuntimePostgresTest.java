package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Isolated PostgreSQL harness. The datasource comes from the shared Testcontainers fixture, whose
 * Flyway migration creates each test's private schema, so the SC-1430 persistence proofs never
 * depend on a developer database or a fixed host port.
 */
@SpringBootTest(classes = ProviderRuntimePostgresTestApplication.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
abstract class AbstractProviderRuntimePostgresTest {

  @DynamicPropertySource
  static void isolatedDatasource(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }
}
