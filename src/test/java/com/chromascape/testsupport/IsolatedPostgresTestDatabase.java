package com.chromascape.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL 16 fixture for Spring integration contexts.
 *
 * <p>The container is shared only as an infrastructure process. Every Spring context receives a
 * fresh schema and its own Flyway history, so no test reads, repairs, or cleans a developer
 * database. The schema name is deliberately generated from a monotonically increasing context
 * number rather than a test class name, which also keeps parallel contexts isolated.
 */
public final class IsolatedPostgresTestDatabase {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("chromascape_isolated_tests")
          .withUsername("chromascape_test")
          .withPassword("chromascape_test");

  private static final AtomicInteger CONTEXT_NUMBER = new AtomicInteger();

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(IsolatedPostgresTestDatabase::stop, "chromascape-test-postgres-stop"));
  }

  private IsolatedPostgresTestDatabase() {}

  /**
   * Registers a new schema-backed datasource for one Spring context.
   *
   * <p>Call this only from a test class's static {@code @DynamicPropertySource} method.
   */
  public static void register(DynamicPropertyRegistry registry) {
    start();
    String schema = "test_context_" + CONTEXT_NUMBER.incrementAndGet();
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=" + schema);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.hikari.schema", () -> schema);
    registry.add("spring.flyway.schemas", () -> schema);
    registry.add("spring.flyway.default-schema", () -> schema);
    registry.add("spring.flyway.create-schemas", () -> true);
    registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
  }

  /** Asserts the repository migration head and rejects any unresolved migration 16 history. */
  public static void assertRepositoryHeadAndNoUnresolvedMigration16(
      Flyway flyway, DataSource dataSource) {
    assertNotNull(flyway, "Flyway must be configured for the isolated test context");
    var current = flyway.info().current();
    assertNotNull(current, "Flyway must migrate the isolated schema");
    assertNotNull(current.getVersion(), "Flyway must expose a repository migration version");
    assertEquals("22", current.getVersion().getVersion(), "Flyway must reach repository head V22");

    String schema = flyway.getConfiguration().getDefaultSchema();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer migration16Rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM \""
                + schema
                + "\".\""
                + flyway.getConfiguration().getTable()
                + "\" WHERE version = '16'",
            Integer.class);
    assertEquals(
        0, migration16Rows, "isolated Flyway history must not contain unresolved migration 16");
    assertTrue(
        flyway.info().all().length > 0,
        "isolated Flyway context must expose repository migrations");
  }

  private static synchronized void start() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  private static synchronized void stop() {
    if (POSTGRES.isRunning()) {
      POSTGRES.stop();
    }
  }
}
