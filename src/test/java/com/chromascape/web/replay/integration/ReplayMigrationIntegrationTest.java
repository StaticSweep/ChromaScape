package com.chromascape.web.replay.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.playback.ReplayPlaybackIndexConvergence;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * REAL PostgreSQL migration coverage for the replay subsystem. Mockito cannot prove that a Flyway
 * chain applies or that a partial-state reconciliation migration converges — only a live server
 * can. Docker availability is a mandatory activation gate for this suite.
 *
 * <p>Covered:
 *
 * <ol>
 *   <li>the full production chain V11 → V22 on an empty database;
 *   <li>V19 from the OLD-column-only state (rename + backfill + drop);
 *   <li>V19 from the NEW-column-only state (no-op convergence);
 *   <li>V19 from the BOTH-columns state with equal values (backfill + drop);
 *   <li>V19 rejection of BOTH-columns rows whose values conflict (fail closed).
 * </ol>
 */
@ExtendWith(OutputCaptureExtension.class)
class ReplayMigrationIntegrationTest {

  /**
   * The replay chain is V11 → V22 EXCLUDING V16 (SC-1173 builder-repair, which references
   * application tables that only Hibernate manages and predates the replay subsystem). Later replay
   * migrations carry no dependency on it.
   */
  static java.nio.file.Path replayMigrationDir() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("replay-migrations");
    String[] scripts = {
      "V10__recording_archive.sql",
      "V11__replay_curation_and_manifest.sql",
      "V12__replay_suites_and_runs.sql",
      "V13__replay_events_checkpoints_and_hashes.sql",
      "V14__replay_mismatches_reports_and_artifacts.sql",
      "V15__replay_schema_alignment.sql",
      "V17__replay_artifact_reference_reuse.sql",
      "V18__replay_checkpoint_artifact_checksum.sql",
      "V19__reconcile_replay_checkpoint_artifacts.sql",
      "V20__replay_finalization_reservation.sql",
      "V24__replay_recording_actions.sql",
      "V30__replay_pre_execution_reports.sql",
      "V31__replay_playback_catalog_pagination.sql",
      "V25__replay_v2_metadata.sql",
      "V26__replay_v2_request_identity.sql"
    };
    for (String script : scripts) {
      try (var in =
          ReplayMigrationIntegrationTest.class.getResourceAsStream("/db/migration/" + script)) {
        java.nio.file.Files.copy(
            in, dir.resolve(script), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return dir;
  }

  @SuppressWarnings("resource")
  private static PostgreSQLContainer<?> postgres(String database) {
    PostgreSQLContainer<?> c =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(database)
            .withUsername("test")
            .withPassword("test")
            .waitingFor(HostPortWaitStrategy.postgres());
    c.start();
    return c;
  }

  private static Connection connect(PostgreSQLContainer<?> pg) throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static Flyway flyway(PostgreSQLContainer<?> pg, String... locations) {
    return Flyway.configure()
        .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
        .locations(
            locations.length > 0 ? locations : new String[] {"filesystem:" + replayMigrations()})
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .outOfOrder(true)
        .load();
  }

  static final java.nio.file.Path MIGRATION_DIR = initMigrationDir();

  static java.nio.file.Path initMigrationDir() {
    try {
      return replayMigrationDir();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to stage replay migration scripts", e);
    }
  }

  static String replayMigrations() {
    return "filesystem:" + MIGRATION_DIR.toAbsolutePath();
  }

  @Test
  void replayChainThroughV31AppliesOnEmptyDatabase() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("replay_chain");
        Connection connection = connect(pg)) {
      int applied = flyway(pg, replayMigrations()).migrate().migrationsExecuted;
      assertTrue(applied >= 11, "the replay chain must apply every pending migration");
      assertDoesNotThrow(() -> flyway(pg, replayMigrations()).validate());
      assertTrue(
          columnExists(connection, "replay_runs", "finalization_reserved_at"),
          "V20 must add finalization_reserved_at");
      assertFalse(
          columnNotNull(connection, "replay_reports", "attempt_id"),
          "V30 must drop NOT NULL from replay_reports.attempt_id");
    }
  }

  @Test
  void v31CreatesPlaybackIndexesWhenDebuggerTablesExist() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v22_playback_indexes");
        Connection connection = connect(pg)) {
      exec(
          connection,
          "CREATE TABLE debug_sessions ("
              + "id BIGSERIAL PRIMARY KEY, start_time TIMESTAMP NOT NULL,"
              + "account_name VARCHAR(100), recording_id VARCHAR(100))");
      exec(
          connection,
          "CREATE TABLE tick_snapshots (id BIGSERIAL PRIMARY KEY, session_id VARCHAR(100) NOT NULL,"
              + "tick BIGINT NOT NULL)");
      exec(
          connection,
          "CREATE TABLE tick_logs (id BIGSERIAL PRIMARY KEY, session_id VARCHAR(100) NOT NULL,"
              + "tick BIGINT NOT NULL)");

      runSingleMigration(connection, "V31__replay_playback_catalog_pagination.sql");
      // A deployment retry must be harmless and must not rewrite captured evidence.
      assertDoesNotThrow(
          () -> runSingleMigration(connection, "V31__replay_playback_catalog_pagination.sql"));

      assertTrue(indexExists(connection, "idx_debug_sessions_playback_keyset"));
      assertTrue(indexExists(connection, "idx_debug_sessions_playback_account_keyset"));
      assertTrue(indexExists(connection, "idx_tick_snapshots_playback_session_tick_id"));
      assertTrue(indexExists(connection, "idx_tick_logs_playback_session_tick_id"));

      String keysetDefinition = indexDefinition(connection, "idx_debug_sessions_playback_keyset");
      assertTrue(keysetDefinition.contains("start_time DESC"));
      assertTrue(keysetDefinition.contains("id DESC"));
      assertTrue(keysetDefinition.toLowerCase().contains("recording_id is not null"));

      String accountDefinition =
          indexDefinition(connection, "idx_debug_sessions_playback_account_keyset");
      assertTrue(accountDefinition.contains("account_name"));
      assertTrue(accountDefinition.contains("start_time DESC"));
      assertTrue(accountDefinition.contains("id DESC"));
      assertTrue(accountDefinition.toLowerCase().contains("recording_id is not null"));
    }
  }

  @Test
  void freshHibernateManagedTablesConvergeAfterFlywayAndRemainStableOnRepeatStartup()
      throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("fresh_hibernate_playback_indexes");
        Connection connection = connect(pg)) {
      // Flyway owns debug_sessions as of V24. Hibernate still creates the tick tables after Flyway,
      // so the post-Hibernate runner must converge their indexes without disturbing the V31
      // debug-session indexes.
      flyway(pg, replayMigrations()).migrate();
      exec(
          connection,
          "CREATE TABLE tick_snapshots (id BIGSERIAL PRIMARY KEY, session_id VARCHAR(100) NOT NULL,"
              + "tick BIGINT NOT NULL)");
      exec(
          connection,
          "CREATE TABLE tick_logs (id BIGSERIAL PRIMARY KEY, session_id VARCHAR(100) NOT NULL,"
              + "tick BIGINT NOT NULL)");

      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      ReplayPlaybackIndexConvergence convergence = new ReplayPlaybackIndexConvergence(dataSource);
      convergence.run(null);
      Map<String, String> firstDefinitions =
          Map.of(
              "idx_debug_sessions_playback_keyset",
                  indexDefinition(connection, "idx_debug_sessions_playback_keyset"),
              "idx_debug_sessions_playback_account_keyset",
                  indexDefinition(connection, "idx_debug_sessions_playback_account_keyset"),
              "idx_tick_snapshots_playback_session_tick_id",
                  indexDefinition(connection, "idx_tick_snapshots_playback_session_tick_id"),
              "idx_tick_logs_playback_session_tick_id",
                  indexDefinition(connection, "idx_tick_logs_playback_session_tick_id"));

      // A repeated application startup must not create duplicates or silently accept definition
      // drift. The runner's live pg_indexes comparison is part of this assertion.
      assertDoesNotThrow(() -> convergence.run(null));
      for (Map.Entry<String, String> entry : firstDefinitions.entrySet()) {
        assertEquals(1, indexCount(connection, entry.getKey()));
        assertEquals(entry.getValue(), indexDefinition(connection, entry.getKey()));
      }
    }
  }

  /** V20-upgraded databases converge to V30 without touching existing rows. */
  @Test
  void v30AppliesOnAV20UpgradedDatabase() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v20_upgrade");
        Connection connection = connect(pg)) {
      // Apply everything EXCEPT V30 so the precondition is the V14 NOT NULL definition.
      java.nio.file.Path dirWithoutV30 = java.nio.file.Files.createTempDirectory("replay-v20");
      try (var files = java.nio.file.Files.list(MIGRATION_DIR)) {
        files
            .filter(p -> !p.getFileName().toString().startsWith("V30"))
            .forEach(
                p -> {
                  try {
                    java.nio.file.Files.copy(
                        p,
                        dirWithoutV30.resolve(p.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
      }
      flyway(pg, "filesystem:" + dirWithoutV30.toAbsolutePath()).migrate();
      assertTrue(
          columnNotNull(connection, "replay_reports", "attempt_id"),
          "precondition: V30 has not yet dropped attempt_id NOT NULL");

      flyway(pg, replayMigrations()).migrate();

      assertFalse(columnNotNull(connection, "replay_reports", "attempt_id"));
      assertDoesNotThrow(() -> flyway(pg, replayMigrations()).validate());
    }
  }

  /** V30 keeps the foreign key: NULL attempt ids insert, bogus non-null values are rejected. */
  @Test
  void v30AllowsNullAttemptIdButKeepsTheForeignKey() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v21_fk");
        Connection connection = connect(pg)) {
      flyway(pg, replayMigrations()).migrate();

      // Minimal run row so the run FK is satisfiable.
      exec(
          connection,
          "INSERT INTO recording_curations (id, recording_id, debug_session_id,"
              + " version, state, role, content_json, content_checksum, created_by)"
              + " VALUES (gen_random_uuid(), 'rec-v21', 'sess-v21', 1, 'PUBLISHED', 'TRAIN',"
              + " '{}'::jsonb, 'sha256:"
              + "c".repeat(64)
              + "', 'tester')");
      exec(
          connection,
          "INSERT INTO scenario_manifests (id, curation_id, recording_id,"
              + " debug_session_id, recording_checksum, debug_session_checksum, compiler_version,"
              + " compiler_schema_version, xrsps_adapter_version, xrsps_build_commit, cache_revision,"
              + " content_revision, seed, fixed_clock_origin, start_tick, end_tick, manifest_json,"
              + " manifest_checksum, completeness, publish_state, created_at)"
              + " VALUES (gen_random_uuid(), (SELECT id FROM recording_curations LIMIT 1), 'rec-v21',"
              + " 'sess-v21', 'sha256:"
              + "a".repeat(64)
              + "', 'sha256:"
              + "b".repeat(64)
              + "', 't', 1, 'ad', 'co', 'ca', 'cr', 'se', TIMESTAMP '2020-01-01 00:00:00', 0, 1,"
              + " '{}'::jsonb, 'sha256:"
              + "d".repeat(64)
              + "', 'COMPLETE', 'PUBLISHED', now())");
      exec(
          connection,
          "INSERT INTO replay_runs (id, mode, manifest_id, manifest_checksum,"
              + " owner_id, idempotency_key, state, phase, stop_policy, control_allowed, branch_key,"
              + " created_at) VALUES ('"
              + UUID.randomUUID()
              + "', 'CAUSAL_SIMULATION', (SELECT id FROM scenario_manifests LIMIT 1), 'sha256:"
              + "0".repeat(64)
              + "', 'o', 'idem-v21-"
              + UUID.randomUUID()
              + "', 'QUEUED', 'PREFLIGHT', 'STOP_ON_FIRST_HARD', false, 'main', now())");

      // NULL attempt_id accepted after V30.
      exec(
          connection,
          "INSERT INTO replay_reports (id, run_id, attempt_id, mode,"
              + " manifest_checksum, outcome, executed_action_count, checkpoint_count,"
              + " mismatch_hard_count, mismatch_soft_count, mismatch_info_count, report_json,"
              + " report_checksum, control_allowed, finalized_at)"
              + " VALUES (gen_random_uuid(), (SELECT id FROM replay_runs LIMIT 1), NULL,"
              + " 'CAUSAL_SIMULATION', 'sha256:"
              + "0".repeat(64)
              + "', 'CANCELLED', 0, 0, 0, 0, 0,"
              + " '{}'::jsonb, 'sha256:"
              + "e".repeat(64)
              + "', false, now())");

      // Non-null but nonexistent attempt violates the retained foreign key. The insert goes to a
      // SECOND run: replay_reports.run_id is UNIQUE, so a second insert for the first run would
      // collide with unique_violation (23505) BEFORE the attempt FK is ever evaluated — the FK
      // contract needs a run that has no report yet.
      UUID secondRunId = UUID.randomUUID();
      exec(
          connection,
          "INSERT INTO replay_runs (id, mode, manifest_id, manifest_checksum,"
              + " owner_id, idempotency_key, state, phase, stop_policy, control_allowed, branch_key,"
              + " created_at) VALUES ('"
              + secondRunId
              + "', 'CAUSAL_SIMULATION', (SELECT id FROM scenario_manifests LIMIT 1), 'sha256:"
              + "0".repeat(64)
              + "', 'o', 'idem-v21-"
              + UUID.randomUUID()
              + "', 'QUEUED', 'PREFLIGHT', 'STOP_ON_FIRST_HARD', false, 'main', now())");
      Exception failure =
          assertThrows(
              Exception.class,
              () ->
                  exec(
                      connection,
                      "INSERT INTO replay_reports (id, run_id, attempt_id, mode,"
                          + " manifest_checksum, outcome, executed_action_count, checkpoint_count,"
                          + " mismatch_hard_count, mismatch_soft_count, mismatch_info_count, report_json,"
                          + " report_checksum, control_allowed, finalized_at)"
                          + " VALUES (gen_random_uuid(), '"
                          + secondRunId
                          + "', '"
                          + UUID.randomUUID()
                          + "', 'CAUSAL_SIMULATION', 'sha256:"
                          + "0".repeat(64)
                          + "', 'FAILED', 0, 0, 0, 0, 0, '{}'::jsonb, 'sha256:"
                          + "f".repeat(64)
                          + "', false, now())"),
              "the foreign key on attempt_id must survive V30");

      // The SQLState is the portable contract — PostgreSQL/JDBC error MESSAGE text is not
      // guaranteed stable across versions, but the FK-violation class is.
      SQLException sql = findSqlException(failure);
      assertEquals(
          "23503",
          sql.getSQLState(),
          "a nonexistent attempt_id must raise a foreign_key_violation (SQLState 23503)");
      // When the server error exposes the constraint name, it must be the replay-report attempt
      // FK (V14 inline REFERENCES default name). A driver that omits the name must not fail.
      String chain = allMessages(failure);
      Matcher matcher = CONSTRAINT_NAME.matcher(chain);
      if (matcher.find()) {
        assertEquals(
            "replay_reports_attempt_id_fkey",
            matcher.group(1),
            "the offending constraint must be the replay_reports.attempt_id foreign key");
      }
    }
  }

  private static final Pattern CONSTRAINT_NAME = Pattern.compile("constraint\\s+\"([^\"]+)\"");

  private static String allMessages(Throwable error) {
    StringBuilder sb = new StringBuilder();
    Throwable current = error;
    while (current != null) {
      if (current.getMessage() != null) {
        sb.append(current.getMessage()).append('\n');
      }
      current = current.getCause();
    }
    return sb.toString();
  }

  /**
   * Walks the full cause chain and returns the first {@link SQLException}; the PostgreSQL/JDBC
   * stack wraps the server error in driver-specific exceptions, so message-text matching is fragile
   * and this structural lookup is the portable contract.
   */
  private static SQLException findSqlException(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    throw new AssertionError("No SQLException in failure chain", error);
  }

  private boolean columnNotNull(Connection c, String table, String column) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='"
                    + table
                    + "' AND column_name='"
                    + column
                    + "' AND is_nullable='NO'")) {
      assertTrue(rs.next());
      return rs.getInt(1) > 0;
    }
  }

  @Test
  void v19ConvergesFromOldColumnOnlyState() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v19_old_only");
        Connection connection = connect(pg)) {
      seedCheckpointTable(connection, true, false);
      exec(
          connection,
          "INSERT INTO replay_checkpoints (id, run_id, attempt_id, tick, snapshot_checksum,"
              + " state_hash, chain_hash, branch_key, created_at, snapshot_artifact_id)"
              + " VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1,"
              + " 'sha256:"
              + "a".repeat(64)
              + "', 's', 'c', 'main', now(), 'sha256:"
              + "b".repeat(64)
              + "')");
      runSingleMigration(connection, "V19__reconcile_replay_checkpoint_artifacts.sql");

      assertFalse(
          columnExists(connection, "replay_checkpoints", "snapshot_artifact_id"),
          "obsolete column must be dropped");
      assertTrue(columnExists(connection, "replay_checkpoints", "snapshot_artifact_checksum"));
      try (Statement st = connection.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT snapshot_artifact_checksum = 'sha256:"
                      + "b".repeat(64)
                      + "' AS preserved,"
                      + " snapshot_artifact_checksum IS NOT NULL AS not_null_art,"
                      + " snapshot_checksum IS NOT NULL AS not_null_internal"
                      + " FROM replay_checkpoints")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean(1), "backfilled checksum must be preserved verbatim");
        assertTrue(rs.getBoolean(2));
        assertTrue(rs.getBoolean(3), "kernel snapshot checksum must be NOT NULL after V19");
      }
    }
  }

  @Test
  void v19AcceptsNewColumnOnlyState() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v19_new_only");
        Connection connection = connect(pg)) {
      seedCheckpointTable(connection, false, true);
      assertDoesNotThrow(
          () -> runSingleMigration(connection, "V19__reconcile_replay_checkpoint_artifacts.sql"));
      assertTrue(columnExists(connection, "replay_checkpoints", "snapshot_artifact_checksum"));
      assertFalse(columnExists(connection, "replay_checkpoints", "snapshot_artifact_id"));
    }
  }

  @Test
  void v19BackfillsAndDropsWhenBothColumnsHoldEqualValues() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v19_both_equal");
        Connection connection = connect(pg)) {
      seedCheckpointTable(connection, true, true);
      String checksum = "sha256:" + "c".repeat(64);
      exec(
          connection,
          "INSERT INTO replay_checkpoints (id, run_id, attempt_id, tick, snapshot_checksum,"
              + " state_hash, chain_hash, branch_key, created_at,"
              + " snapshot_artifact_id, snapshot_artifact_checksum)"
              + " VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1,"
              + " 'sha256:"
              + "d".repeat(64)
              + "', 's', 'c', 'main', now(), '"
              + checksum
              + "', NULL)");
      assertDoesNotThrow(
          () -> runSingleMigration(connection, "V19__reconcile_replay_checkpoint_artifacts.sql"));
      try (Statement st = connection.createStatement();
          ResultSet rs = st.executeQuery("SELECT count(*) FROM replay_checkpoints")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1), "backfill must preserve every row");
      }
      assertFalse(columnExists(connection, "replay_checkpoints", "snapshot_artifact_id"));
    }
  }

  @Test
  void v19RejectsConflictingOldAndNewChecksums() throws Exception {
    try (PostgreSQLContainer<?> pg = postgres("v19_both_conflict");
        Connection connection = connect(pg)) {
      seedCheckpointTable(connection, true, true);
      exec(
          connection,
          "INSERT INTO replay_checkpoints (id, run_id, attempt_id, tick, snapshot_checksum,"
              + " state_hash, chain_hash, branch_key, created_at,"
              + " snapshot_artifact_id, snapshot_artifact_checksum)"
              + " VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1,"
              + " 'sha256:"
              + "e".repeat(64)
              + "', 's', 'c', 'main', now(),"
              + " 'sha256:"
              + "1".repeat(64)
              + "', 'sha256:"
              + "2".repeat(64)
              + "')");
      Exception failure =
          assertThrows(
              Exception.class,
              () ->
                  runSingleMigration(connection, "V19__reconcile_replay_checkpoint_artifacts.sql"),
              "conflicting evidence must fail the migration closed");
      assertTrue(
          String.valueOf(failure.getMessage()).contains("V19 reconciliation conflict"),
          "the failure must name the conflicting invariant");
    }
  }

  // ── helpers ──

  /** Minimal replay_checkpoints in a requested legacy shape, independent of the full chain. */
  private void seedCheckpointTable(Connection c, boolean oldColumn, boolean newColumn)
      throws SQLException {
    StringBuilder ddl =
        new StringBuilder(
            "CREATE TABLE replay_checkpoints ("
                + "id UUID PRIMARY KEY DEFAULT gen_random_uuid(),"
                + "run_id UUID NOT NULL,"
                + "attempt_id UUID NOT NULL,"
                + "tick BIGINT NOT NULL,"
                + "snapshot_checksum VARCHAR(71),"
                + "state_hash VARCHAR NOT NULL,"
                + "chain_hash VARCHAR NOT NULL,"
                + "branch_key VARCHAR NOT NULL DEFAULT 'main',"
                + "created_at TIMESTAMP NOT NULL DEFAULT now()");
    if (oldColumn) {
      ddl.append(", snapshot_artifact_id VARCHAR(71)");
    }
    if (newColumn) {
      ddl.append(", snapshot_artifact_checksum VARCHAR(71)");
    }
    ddl.append(")");
    exec(c, ddl.toString());
  }

  private void runSingleMigration(Connection c, String resource) throws Exception {
    String sql;
    try (var in = getClass().getResourceAsStream("/db/migration/" + resource)) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private boolean columnExists(Connection c, String table, String column) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='"
                    + table
                    + "' AND column_name='"
                    + column
                    + "'")) {
      assertTrue(rs.next());
      return rs.getInt(1) > 0;
    }
  }

  private boolean indexExists(Connection c, String indexName) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = '"
                    + indexName
                    + "'")) {
      assertTrue(rs.next());
      return rs.getInt(1) == 1;
    }
  }

  private int indexCount(Connection c, String indexName) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = '"
                    + indexName
                    + "'")) {
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }

  private String indexDefinition(Connection c, String indexName) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = '"
                    + indexName
                    + "'")) {
      assertTrue(rs.next(), "index definition must exist for " + indexName);
      return rs.getString(1);
    }
  }

  private void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }
}
