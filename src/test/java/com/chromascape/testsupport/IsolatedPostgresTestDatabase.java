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
    Database database = newDatabase();
    registry.add(
        "spring.datasource.url", () -> database.jdbcUrl() + "?currentSchema=" + database.schema());
    registry.add("spring.datasource.username", database::username);
    registry.add("spring.datasource.password", database::password);
    registry.add("spring.datasource.hikari.schema", database::schema);
    registry.add("spring.flyway.schemas", database::schema);
    registry.add("spring.flyway.default-schema", database::schema);
    registry.add("spring.flyway.create-schemas", () -> true);
    registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    registry.add("spring.jpa.properties.hibernate.default_schema", database::schema);
  }

  /** Allocates a schema and returns the connection properties for a manually managed context. */
  public static Database newDatabase() {
    start();
    String schema = "test_context_" + CONTEXT_NUMBER.incrementAndGet();
    return new Database(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), schema);
  }

  public record Database(String jdbcUrl, String username, String password, String schema) {}

  /** Asserts the repository migration head and rejects any unresolved migration 16 history. */
  public static void assertRepositoryHeadAndNoUnresolvedMigration16(
      Flyway flyway, DataSource dataSource) {
    assertNotNull(flyway, "Flyway must be configured for the isolated test context");
    var current = flyway.info().current();
    assertNotNull(current, "Flyway must migrate the isolated schema");
    assertNotNull(current.getVersion(), "Flyway must expose a repository migration version");
    assertEquals("44", current.getVersion().getVersion(), "Flyway must reach repository head V44");

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
    assertFreshSchemaOwnsRecordingDebugContracts(jdbc, schema);
  }

  /**
   * Fresh-database SC-810 ownership proof: Flyway alone must produce the recording/debug tables,
   * the archive column, the recording-action/receipt/packet foreign keys and the playback indexes.
   * A migration that merely succeeds while silently omitting them fails this gate.
   */
  private static void assertFreshSchemaOwnsRecordingDebugContracts(
      JdbcTemplate jdbc, String schema) {
    assertTrue(
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name IN"
                    + " ('recording_sessions','debug_sessions','replay_recording_actions',"
                    + "'replay_tick_batch_receipts','packet_observations')",
                Integer.class,
                schema)
            == 5,
        "Flyway must own recording/debug tables on a fresh schema");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'recording_sessions'"
                + " AND column_name = 'archived_at'",
            Integer.class,
            schema),
        "Flyway must own recording_sessions.archived_at on a fresh schema");
    for (String constraint :
        new String[] {
          "fk_replay_recording_actions_session",
          "fk_replay_recording_actions_recording",
          "fk_replay_tick_batch_receipt_session",
          "fk_packet_observations_session"
        }) {
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pg_constraint c"
                  + " JOIN pg_class t ON t.oid = c.conrelid"
                  + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                  + " WHERE n.nspname = ? AND c.conname = ?",
              Integer.class,
              schema,
              constraint),
          "Flyway must own foreign key " + constraint + " on a fresh schema");
    }
    for (String index :
        new String[] {
          "idx_recording_sessions_archived_at",
          "idx_debug_sessions_playback_keyset",
          "idx_debug_sessions_playback_account_keyset"
        }) {
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
              Integer.class,
              schema,
              index),
          "Flyway must own index " + index + " on a fresh schema");
    }
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
