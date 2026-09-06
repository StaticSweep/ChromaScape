package com.chromascape.web.debug.tickdebugger.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL coverage for the durable tick-batch receipt constraint boundary.
 *
 * <p>Every test runs inside its own isolated schema with its own Flyway history, so tests never
 * share tables and never depend on JUnit method order. The contract depends on PostgreSQL unique
 * constraints, transactional rollback, foreign-key retention, and concurrent conflict handling,
 * which is why these tests run raw JDBC statements against Testcontainers PostgreSQL 16 rather than
 * instantiating the Spring repositories or services. The helpers mirror the repository {@code
 * INSERT ... ON CONFLICT DO NOTHING} statement textually so PostgreSQL remains the arbiter of the
 * constraint contract; genuine Spring service/repository concurrency coverage lives in {@link
 * TickBatchDurabilityIntegrationTest}.
 */
@Testcontainers
class ReplayTickBatchReceiptIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("tick_batch_receipts")
          .withUsername("test")
          .withPassword("test");

  /** One isolated schema per test; created fresh and dropped after the test finishes. */
  private String schema;

  @org.junit.jupiter.api.BeforeEach
  void createIsolatedSchema() throws Exception {
    schema = "receipt_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    try (Connection admin = connection();
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE SCHEMA \"" + schema + "\"");
    }
  }

  @org.junit.jupiter.api.AfterEach
  void dropIsolatedSchema() throws Exception {
    if (schema != null) {
      try (Connection admin = connection();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
      } catch (SQLException ignored) {
        // Best effort: the container is disposable and the next test uses a fresh schema.
      } finally {
        schema = null;
      }
    }
  }

  @Test
  void freshMigrationReachesV38WithCascadeAndNoRedundantTickIndex() throws Exception {
    try (Connection connection = connection()) {
      setSchema(connection);
      createDebugSessionsTable(connection);
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      assertFlywayVersions(connection, "35", "37", "38");

      assertEquals("c", foreignKeyDeleteRule(connection), "receipt retention must cascade");
      assertEquals(0, indexCount(connection, "idx_replay_tick_batch_receipt_session_tick"));
      assertEquals(1, constraintCount(connection, "uq_replay_tick_batch_receipt_session_tick"));
      assertEquals(1, constraintCount(connection, "uq_replay_tick_batch_receipt_session_batch"));

      String sessionId = "debug-retention-1";
      insertSession(connection, sessionId);
      insertReceipt(connection, sessionId, UUID.randomUUID(), 42, checksum('a'));
      execute(
          connection,
          qualified("DELETE FROM debug_sessions WHERE session_id = '" + sessionId + "'"));
      assertEquals(
          0,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = '"
                      + sessionId
                      + "'")));
    }
  }

  @Test
  void upgradeFromAppliedV35ChangesOnlyRetentionPolicyAndPreservesRows() throws Exception {
    try (Connection connection = connection()) {
      setSchema(connection);
      createDebugSessionsTable(connection);
      migrate("V35__replay_tick_batch_receipts.sql");
      assertFlywayVersions(connection, "35");
      assertEquals(
          "r", foreignKeyDeleteRule(connection), "V35 must retain RESTRICT before upgrade");
      String sessionId = "debug-upgrade-1";
      insertSession(connection, sessionId);
      insertReceipt(connection, sessionId, UUID.randomUUID(), 7, checksum('b'));

      // The upgrade invocation retains V35 in the same location so Flyway validation sees the
      // already-applied baseline; only V37/V38 are pending and only they change the schema.
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      assertFlywayVersions(connection, "35", "37", "38");

      assertEquals(
          1,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = '"
                      + sessionId
                      + "'")));
      assertEquals("c", foreignKeyDeleteRule(connection));
      assertEquals(0, indexCount(connection, "idx_replay_tick_batch_receipt_session_tick"));
      assertEquals(1, constraintCount(connection, "uq_replay_tick_batch_receipt_session_tick"));
      execute(
          connection,
          qualified("DELETE FROM debug_sessions WHERE session_id = '" + sessionId + "'"));
      assertEquals(
          0,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = '"
                      + sessionId
                      + "'")));
    }
  }

  @Test
  void receiptAndEvidenceRollbackTogetherBeforeCommit() throws Exception {
    try (Connection connection = connection()) {
      setSchema(connection);
      createDebugSessionsTable(connection);
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      insertSession(connection, "debug-rollback-1");
      execute(
          connection,
          qualified(
              "CREATE TABLE tick_snapshots (session_id varchar(100) NOT NULL, tick bigint NOT NULL)"));

      connection.setAutoCommit(false);
      insertReceipt(connection, "debug-rollback-1", UUID.randomUUID(), 9, checksum('c'));
      execute(
          connection,
          qualified("INSERT INTO tick_snapshots(session_id, tick) VALUES ('debug-rollback-1', 9)"));
      assertThrows(
          SQLException.class,
          () -> {
            execute(
                connection,
                qualified(
                    "INSERT INTO replay_tick_batch_receipts(session_id, batch_id, tick, payload_checksum, schema_name, persisted_at) "
                        + "VALUES ('debug-rollback-1', '"
                        + UUID.randomUUID()
                        + "', 9, '"
                        + checksum('d')
                        + "', 'tick-batch/v2', CURRENT_TIMESTAMP)"));
          });
      connection.rollback();
      connection.setAutoCommit(true);

      assertEquals(
          0,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = 'debug-rollback-1'")));
      assertEquals(
          0,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM tick_snapshots WHERE session_id = 'debug-rollback-1'")));
    }
  }

  @Test
  void concurrentExactDuplicatesProduceOneReceipt() throws Exception {
    try (Connection setup = connection()) {
      setSchema(setup);
      createDebugSessionsTable(setup);
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      insertSession(setup, "debug-race-exact");
    }
    UUID batchId = UUID.randomUUID();
    String payloadChecksum = checksum('e');
    List<Integer> results =
        raceReceipts(
            "debug-race-exact",
            batchId,
            10,
            payloadChecksum,
            "debug-race-exact",
            batchId,
            10,
            payloadChecksum);
    assertEquals(1, results.stream().mapToInt(Integer::intValue).sum());
    try (Connection connection = connection()) {
      setSchema(connection);
      assertEquals(
          1,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = 'debug-race-exact'")));
    }
  }

  @Test
  void concurrentConflictingTicksProduceOneDeterministicDatabaseWinner() throws Exception {
    try (Connection setup = connection()) {
      setSchema(setup);
      createDebugSessionsTable(setup);
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      insertSession(setup, "debug-race-conflict");
    }
    List<Integer> results =
        raceReceipts(
            "debug-race-conflict",
            UUID.randomUUID(),
            11,
            checksum('f'),
            "debug-race-conflict",
            UUID.randomUUID(),
            11,
            checksum('0'));
    assertEquals(1, results.stream().mapToInt(Integer::intValue).sum());
    try (Connection connection = connection()) {
      setSchema(connection);
      assertEquals(
          1,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = 'debug-race-conflict' AND tick = 11")));
    }
  }

  /** Races the mirrored {@code insertIfAbsent} statement through the constraint conflict path. */
  @Test
  void jdbcInsertIfAbsentMirrorDuplicateAndConflictRaces() throws Exception {
    try (Connection setup = connection()) {
      setSchema(setup);
      createDebugSessionsTable(setup);
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      insertSession(setup, "debug-race-repo");
    }
    // Exact duplicates through the repository statement: exactly one row wins.
    UUID batchId = UUID.randomUUID();
    List<Integer> duplicates =
        raceReceipts(
            "debug-race-repo",
            batchId,
            20,
            checksum('g'),
            "debug-race-repo",
            batchId,
            20,
            checksum('g'));
    assertEquals(1, duplicates.stream().mapToInt(Integer::intValue).sum());
    // Conflicting same-tick batches: exactly one deterministic winner.
    List<Integer> conflicts =
        raceReceipts(
            "debug-race-repo",
            UUID.randomUUID(),
            21,
            checksum('h'),
            "debug-race-repo",
            UUID.randomUUID(),
            21,
            checksum('i'));
    assertEquals(1, conflicts.stream().mapToInt(Integer::intValue).sum());
    try (Connection connection = connection()) {
      setSchema(connection);
      assertEquals(
          1,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = 'debug-race-repo' AND tick = 20")));
      assertEquals(
          1,
          scalarInt(
              connection,
              qualified(
                  "SELECT count(*) FROM replay_tick_batch_receipts WHERE session_id = 'debug-race-repo' AND tick = 21")));
    }
  }

  /**
   * Races mirrored unique-constraint inserts against the real schema. No Spring repository or
   * service is instantiated here: the helper below only repeats the repository's {@code INSERT ...
   * ON CONFLICT DO NOTHING} text so PostgreSQL unique constraints remain the arbiter while the
   * winner-row contract is covered at the database boundary.
   */
  @Test
  void jdbcDuplicateAndConflictResolutionAgainstRealSchema() throws Exception {
    try (Connection setup = connection()) {
      setSchema(setup);
      createDebugSessionsTable(setup);
      migrate(
          "V35__replay_tick_batch_receipts.sql",
          "V37__replay_tick_batch_receipt_retention.sql",
          "V38__replay_tick_batch_receipt_retention_guard.sql");
      insertSession(setup, "debug-race-service");
    }
    UUID batchId = UUID.randomUUID();
    String checksumValue = checksum('j');
    // First insert wins through the real statement; the duplicate observes the winner row.
    assertEquals(1, insertReceiptReturningCount("debug-race-service", batchId, 30, checksumValue));
    assertEquals(0, insertReceiptReturningCount("debug-race-service", batchId, 30, checksumValue));
    // A conflicting same-tick insert loses; the winner row is unchanged.
    assertEquals(
        0, insertReceiptReturningCount("debug-race-service", UUID.randomUUID(), 30, checksum('k')));
    try (Connection connection = connection()) {
      setSchema(connection);
      assertEquals(
          checksumValue,
          scalarString(
              connection,
              qualified(
                  "SELECT payload_checksum FROM replay_tick_batch_receipts WHERE session_id = 'debug-race-service' AND tick = 30")));
    }
  }

  private List<Integer> raceReceipts(
      String sessionA,
      UUID batchA,
      long tickA,
      String checksumA,
      String sessionB,
      UUID batchB,
      long tickB,
      String checksumB)
      throws Exception {
    String testSchema = schema;
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> futures =
          List.of(
              executor.submit(
                  () ->
                      insertReceiptAfterBarrier(
                          testSchema, barrier, sessionA, batchA, tickA, checksumA)),
              executor.submit(
                  () ->
                      insertReceiptAfterBarrier(
                          testSchema, barrier, sessionB, batchB, tickB, checksumB)));
      List<Integer> result = new ArrayList<>();
      for (Future<Integer> future : futures) {
        result.add(future.get(20, TimeUnit.SECONDS));
      }
      return result;
    } finally {
      executor.shutdownNow();
    }
  }

  private int insertReceiptAfterBarrier(
      String testSchema,
      CyclicBarrier barrier,
      String sessionId,
      UUID batchId,
      long tick,
      String payloadChecksum)
      throws Exception {
    try (Connection connection = connection()) {
      setSchema(connection, testSchema);
      connection.setAutoCommit(false);
      barrier.await(10, TimeUnit.SECONDS);
      int inserted =
          insertReceiptReturningCount(connection, sessionId, batchId, tick, payloadChecksum);
      connection.commit();
      return inserted;
    }
  }

  private int insertReceiptReturningCount(
      String sessionId, UUID batchId, long tick, String payloadChecksum) throws Exception {
    try (Connection connection = connection()) {
      setSchema(connection);
      return insertReceiptReturningCount(connection, sessionId, batchId, tick, payloadChecksum);
    }
  }

  private int insertReceiptReturningCount(
      Connection connection, String sessionId, UUID batchId, long tick, String payloadChecksum)
      throws SQLException {
    // Mirrors ReplayTickBatchReceiptRepository.insertIfAbsent: INSERT ... ON CONFLICT DO NOTHING.
    try (var statement =
        connection.prepareStatement(
            qualified(
                "INSERT INTO replay_tick_batch_receipts "
                    + "(session_id, batch_id, tick, payload_checksum, schema_name, persisted_at) "
                    + "VALUES (?, ?, ?, ?, 'tick-batch/v2', CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING"))) {
      statement.setString(1, sessionId);
      statement.setObject(2, batchId);
      statement.setLong(3, tick);
      statement.setString(4, payloadChecksum);
      return statement.executeUpdate();
    }
  }

  private Connection connection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Pins every statement on a connection to this test's isolated schema. */
  private void setSchema(Connection connection) throws SQLException {
    setSchema(connection, schema);
  }

  private void setSchema(Connection connection, String testSchema) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO \"" + testSchema + "\", public");
    }
  }

  /**
   * Qualifies bare table references to the isolated schema; already-qualified names are untouched.
   */
  private String qualified(String sql) {
    String scoped =
        sql.replaceAll(
            "(?<![\"\\w.])replay_tick_batch_receipts",
            "\"" + schema + "\".replay_tick_batch_receipts");
    scoped = scoped.replaceAll("(?<![\"\\w.])debug_sessions", "\"" + schema + "\".debug_sessions");
    scoped = scoped.replaceAll("(?<![\"\\w.])tick_snapshots", "\"" + schema + "\".tick_snapshots");
    return scoped;
  }

  private void migrate(String... scripts) throws Exception {
    Path directory = Files.createTempDirectory("tick-batch-migrations");
    try {
      for (String script : scripts) {
        try (var input = getClass().getResourceAsStream("/db/migration/" + script)) {
          if (input == null) {
            throw new IOException("Missing migration resource: " + script);
          }
          String content =
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          Files.writeString(
              directory.resolve(script),
              rewriteMigrationForIsolatedSchema(content),
              java.nio.charset.StandardCharsets.UTF_8);
        }
      }
      Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .createSchemas(false)
          .locations("filesystem:" + directory)
          .load()
          .migrate();
    } finally {
      try (var paths = Files.walk(directory)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  /**
   * Token-aware rewrite of the checked-in migrations for one isolated schema.
   *
   * <p>Qualified {@code public.table} references are replaced first. Bare table names are then
   * qualified only when they are true table references: the negative lookbehind skips
   * already-qualified names (preceded by {@code .} or {@code "}) and the trailing word guard skips
   * substrings inside longer constraint/index identifiers. Catalog guards comparing {@code
   * schema_ref.nspname = 'public'} are retargeted to the isolated schema so V37/V38 conditional
   * logic recognizes the test schema.
   */
  private String rewriteMigrationForIsolatedSchema(String content) {
    String rewritten =
        content
            .replace(
                "public.replay_tick_batch_receipts",
                "\"" + schema + "\".replay_tick_batch_receipts")
            .replace("public.debug_sessions", "\"" + schema + "\".debug_sessions")
            .replace(
                "public.idx_replay_tick_batch_receipt_session_tick",
                "\"" + schema + "\".idx_replay_tick_batch_receipt_session_tick")
            .replace("schema_ref.nspname = 'public'", "schema_ref.nspname = '" + schema + "'");
    rewritten =
        rewritten.replaceAll(
            "(?<![\"\\w.])replay_tick_batch_receipts(?![\\w])",
            "\"" + schema + "\".replay_tick_batch_receipts");
    rewritten =
        rewritten.replaceAll(
            "(?<![\"\\w.])debug_sessions(?![\\w])", "\"" + schema + "\".debug_sessions");
    rewritten =
        rewritten.replaceAll(
            "(?<![\"\\w.])tick_snapshots(?![\\w])", "\"" + schema + "\".tick_snapshots");
    return rewritten;
  }

  /** Asserts the schema-local Flyway history contains exactly the expected versions in order. */
  private void assertFlywayVersions(Connection connection, String... versions) throws SQLException {
    List<String> actual = new ArrayList<>();
    try (var statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT version FROM \""
                    + schema
                    + "\".flyway_schema_history ORDER BY installed_rank")) {
      while (result.next()) {
        actual.add(result.getString(1));
      }
    }
    assertEquals(
        List.of(versions), actual, "schema-local Flyway history must match applied migrations");
  }

  private void createDebugSessionsTable(Connection connection) throws SQLException {
    execute(
        connection, qualified("CREATE TABLE debug_sessions (session_id varchar(100) PRIMARY KEY)"));
  }

  private void insertSession(Connection connection, String sessionId) throws SQLException {
    execute(
        connection,
        qualified("INSERT INTO debug_sessions(session_id) VALUES ('" + sessionId + "')"));
  }

  private void insertReceipt(
      Connection connection, String sessionId, UUID batchId, long tick, String checksum)
      throws SQLException {
    execute(
        connection,
        qualified(
            "INSERT INTO replay_tick_batch_receipts "
                + "(session_id, batch_id, tick, payload_checksum, schema_name, persisted_at) VALUES ('"
                + sessionId
                + "', '"
                + batchId
                + "', "
                + tick
                + ", '"
                + checksum
                + "', 'tick-batch/v2', CURRENT_TIMESTAMP)"));
  }

  private String foreignKeyDeleteRule(Connection connection) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT rc.delete_rule FROM information_schema.referential_constraints rc "
                + "JOIN information_schema.table_constraints tc ON tc.constraint_name = rc.constraint_name "
                + "WHERE tc.table_schema = ? AND tc.table_name = 'replay_tick_batch_receipts' "
                + "AND tc.constraint_name = 'fk_replay_tick_batch_receipt_session'")) {
      statement.setString(1, schema);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "receipt session FK must exist");
        return result.getString(1).toLowerCase();
      }
    }
  }

  private int indexCount(Connection connection, String indexName) throws SQLException {
    return scalarInt(
        connection,
        "SELECT count(*) FROM pg_indexes WHERE schemaname = '"
            + schema
            + "' AND indexname = '"
            + indexName
            + "'");
  }

  private int constraintCount(Connection connection, String constraintName) throws SQLException {
    return scalarInt(
        connection,
        "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
            + "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = '"
            + schema
            + "' AND t.relname = 'replay_tick_batch_receipts' AND c.conname = '"
            + constraintName
            + "'");
  }

  private int scalarInt(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        ResultSet result = statement.executeQuery(qualified(sql))) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private String scalarString(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        ResultSet result = statement.executeQuery(qualified(sql))) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String checksum(char suffix) {
    return "sha256:" + String.valueOf(suffix).repeat(64);
  }
}
