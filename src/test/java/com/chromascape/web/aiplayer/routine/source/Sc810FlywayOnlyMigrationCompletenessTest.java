package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SC-810 Flyway-only migration-completeness proof, in the same style as {@code
 * Sc967FlywaySchemaTest}: plain JDBC and a standalone {@link Flyway} configuration, no Spring
 * context and no Hibernate at all — so there is no {@code ddl-auto} of any kind to silently
 * paper over a migration gap. Runs against the configured local test PostgreSQL ({@code
 * localhost:5433/chromascape_test}) inside a dedicated per-run schema that is dropped afterwards;
 * never touches the shared {@code chromascape} developer database.
 *
 * <p>An earlier attempt booted the full {@link Sc810PostgresTestConfiguration} Spring context
 * with {@code ddl-auto=validate} instead. That surfaced real but entirely unrelated gaps —
 * {@code DecisionEntity}'s {@code decisions} table and {@code ScriptFailureTriage}'s {@code
 * script_failure_triage} table are never created by any migration, masked everywhere else by
 * {@code ddl-auto=update} — because {@code @EntityScan(basePackageClasses = ScriptStabilizationRun
 * .class)} scans that class's whole package, not just that one class, sweeping in every sibling
 * entity in {@code com.chromascape.web.accounttask.entity}. This test avoids Hibernate entirely so
 * it proves exactly the SC-810 source/capture/run migration contract (V21, V36) without depending
 * on that scan's breadth.
 *
 * <p>This raw-JDBC approach found a third, separate gap the same way: no migration anywhere
 * creates {@code script_stabilization_run} (the exact table {@code ScriptStabilizationRun} maps
 * to; a related but different table, {@code script_stabilization_snapshot}, does exist). {@code
 * CurrentSliceRunService}'s own code attributes that entity to SC-572, not SC-810; {@link
 * Sc810PostgresTestConfiguration} only consumes it (as the stabilization PASS gate) rather than
 * owning its schema, so this test deliberately does not assert its presence. All three gaps are
 * real and are reported separately in the SC-810 delivery evidence; fixing them is out of scope
 * here.
 */
class Sc810FlywayOnlyMigrationCompletenessTest {

  private static final String SCHEMA = "sc810_migration_completeness_test";
  private static final String URL =
      System.getenv("SPRING_DATASOURCE_URL") == null
          ? "jdbc:postgresql://localhost:5433/chromascape_test?sslmode=disable"
          : System.getenv("SPRING_DATASOURCE_URL");
  private static final String USER = "chromascape";
  private static final String PASSWORD = "chromascape123";

  @BeforeAll
  static void migrateFromEmptyToHead() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      statement.execute("CREATE SCHEMA " + SCHEMA);
    }
    ClassicConfiguration configuration = configuration();
    MigrateResult result = new Flyway(configuration).migrate();
    assertTrue(migratedTo(result, "45"), "SC-810 acceptance checkout must migrate through V45");

    // Flyway's own validate() re-checks every applied migration's checksum against this
    // checkout's local files — the exact proof step 4 needs for "is this an environmental
    // mismatch or a real defect", run here against a from-empty schema this checkout controls,
    // never against a database another checkout may have already migrated.
    new Flyway(configuration).validate();
  }

  @AfterAll
  static void dropSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  @Test
  void flywayAloneOwnsTheSourceContract() throws Exception {
    Set<String> expectedTables =
        Set.of(
            "current_slice_runs",
            "routine_capture_records",
            "routine_capture_operations",
            "source_manifest_references",
            "source_run_bindings",
            "source_content_objects",
            "source_read_audit_events");
    Set<String> present = tables();
    assertTrue(
        present.containsAll(expectedTables),
        "missing SC-810 tables; present=" + present + " expected=" + expectedTables);

    assertTrue(
        indexExists("source_manifest_references", "uk_source_manifest_references_manifest_hash"));
    assertTrue(indexIsUnique("source_manifest_references", "uk_source_manifest_references_manifest_hash"));
    assertTrue(
        indexExists("source_manifest_references", "uk_source_manifest_references_snapshot_id"));
    assertTrue(indexExists("source_run_bindings", "uk_source_run_bindings_run_id"));
    assertTrue(indexExists("source_run_bindings", "uk_source_run_bindings_recording_id"));
    assertTrue(indexExists("source_content_objects", "uk_source_content_objects_content_hash"));
    assertTrue(
        foreignKeyExists("source_run_bindings", "fk_source_run_bindings_manifest_hash"),
        "source_run_bindings must reference an already-verified source_manifest_references row");
    assertTrue(
        triggerExists("source_manifest_references", "trg_sc810_immutable_manifest_reference"));
    assertTrue(triggerExists("source_run_bindings", "trg_sc810_immutable_run_binding"));
    assertTrue(triggerExists("source_content_objects", "trg_sc810_immutable_content_object"));
  }

  /**
   * Exercises the real unique-manifest-hash constraint from V21 with plain SQL — no Hibernate, no
   * ORM — against the Flyway-only schema this class just migrated.
   */
  @Test
  void uniqueManifestHashIndexRejectsIdentityReuseWithDifferentBytes() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      connection.setAutoCommit(true);
      insertManifestReference(
          connection, "sm-flyway-only-1", "sha256:" + "1".repeat(64), "snap_flyway_only_1");

      SQLException conflict =
          assertThrows(
              SQLException.class,
              () ->
                  insertManifestReference(
                      connection,
                      "sm-flyway-only-2",
                      "sha256:" + "1".repeat(64),
                      "snap_flyway_only_2"),
              "the unique manifest-hash index must reject identity reuse with different bytes");
      assertTrue(
          conflict.getSQLState() != null && conflict.getSQLState().startsWith("23"),
          "expected a PostgreSQL integrity-constraint-violation class (23xxx), got "
              + conflict.getSQLState());
    }
  }

  /**
   * Exercises the real immutability trigger from V21 with plain SQL, proving it is what rejects
   * mutation — not anything an ORM might otherwise allow or paper over.
   */
  @Test
  void immutabilityTriggerRejectsAnyMutationOfAPersistedManifestReference() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      connection.setAutoCommit(true);
      String id =
          insertManifestReference(
              connection, "sm-flyway-only-3", "sha256:" + "2".repeat(64), "snap_flyway_only_3");

      try (Statement statement = connection.createStatement()) {
        SQLException rejected =
            assertThrows(
                SQLException.class,
                () ->
                    statement.execute(
                        "UPDATE "
                            + SCHEMA
                            + ".source_manifest_references SET manifest_json = '{\"tampered\":"
                            + " true}' WHERE id = '"
                            + id
                            + "'"),
                "the SC-810 immutability trigger must reject identity mutation");
        assertTrue(
            rejected.getMessage() != null
                && rejected.getMessage().contains("SC-810 source manifest identity is immutable"),
            "expected the SC-810 trigger's own exception message, got: " + rejected.getMessage());
      }

      try (Statement statement = connection.createStatement();
          ResultSet stored =
              statement.executeQuery(
                  "SELECT manifest_json FROM "
                      + SCHEMA
                      + ".source_manifest_references WHERE id = '"
                      + id
                      + "'")) {
        assertTrue(stored.next());
        assertFalse(
            stored.getString(1).contains("tampered"),
            "the immutable row must survive the rejected update");
      }
    }
  }

  private String insertManifestReference(
      Connection connection, String idSeed, String manifestHash, String snapshotId)
      throws SQLException {
    String id = deterministicUuid(idSeed);
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO "
              + SCHEMA
              + ".source_manifest_references (id, task_key, schema_version, manifest_hash,"
              + " snapshot_id, repository_id, commit_id, tree_id, build_id, artifact_digest,"
              + " verification_state, source_provenance, retention_class, confidentiality,"
              + " environment, created_at, manifest_json) VALUES ('"
              + id
              + "', 'EXAMPLE_TASK', 'task-source-manifest/v1', '"
              + manifestHash
              + "', '"
              + snapshotId
              + "', 'micro6', '"
              + "0123456789abcdef0123456789abcdef01234567"
              + "', '"
              + "fedcba9876543210fedcba9876543210fedcba98"
              + "', 'micro6-build-1', 'sha256:"
              + "3".repeat(64)
              + "', 'VERIFIED', 'VERIFIED', 'DEBUG', 'INTERNAL', 'production', now(),"
              + " '{\"schema\":\"task-source-manifest/v1\"}')");
    }
    return id;
  }

  private static String deterministicUuid(String seed) {
    // Deterministic per-test-method UUIDs (valid hex only) so reruns within the same schema do
    // not collide.
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("MD5")
              .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(32);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      String padded = hex.toString();
      return padded.substring(0, 8)
          + "-"
          + padded.substring(8, 12)
          + "-"
          + padded.substring(12, 16)
          + "-"
          + padded.substring(16, 20)
          + "-"
          + padded.substring(20, 32);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("MD5 unavailable", exception);
    }
  }

  private static ClassicConfiguration configuration() {
    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setUrl(URL);
    configuration.setUser(USER);
    configuration.setPassword(PASSWORD);
    configuration.setDefaultSchema(SCHEMA);
    configuration.setLocationsAsStrings("classpath:db/migration");
    configuration.setBaselineOnMigrate(true);
    configuration.setBaselineVersion("0");
    configuration.setOutOfOrder(true);
    return configuration;
  }

  private static boolean migratedTo(MigrateResult result, String version) {
    for (org.flywaydb.core.api.output.MigrateOutput output : result.migrations) {
      if (version.equals(output.version == null ? "" : output.version)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> query(String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        ResultSet result = connection.createStatement().executeQuery(sql)) {
      while (result.next()) {
        rows.add(result.getString(1));
      }
    }
    return rows;
  }

  private static Set<String> tables() throws Exception {
    return Set.copyOf(query("select tablename from pg_tables where schemaname = '" + SCHEMA + "'"));
  }

  private static boolean indexExists(String table, String index) throws Exception {
    return !query(
            "select indexname from pg_indexes where schemaname = '"
                + SCHEMA
                + "' and tablename = '"
                + table
                + "' and indexname = '"
                + index
                + "'")
        .isEmpty();
  }

  private static boolean indexIsUnique(String table, String index) throws Exception {
    return query(
                "select indexdef from pg_indexes where schemaname = '"
                    + SCHEMA
                    + "' and tablename = '"
                    + table
                    + "' and indexname = '"
                    + index
                    + "' and indexdef like 'CREATE UNIQUE INDEX%'")
            .size()
        == 1;
  }

  private static boolean foreignKeyExists(String table, String constraint) throws Exception {
    return !query(
            "select c.conname from pg_constraint c"
                + " join pg_class t on t.oid = c.conrelid"
                + " join pg_namespace n on n.oid = t.relnamespace"
                + " where n.nspname = '"
                + SCHEMA
                + "' and t.relname = '"
                + table
                + "' and c.conname = '"
                + constraint
                + "'")
        .isEmpty();
  }

  private static boolean triggerExists(String table, String trigger) throws Exception {
    return !query(
            "select tgname from pg_trigger tg"
                + " join pg_class t on t.oid = tg.tgrelid"
                + " join pg_namespace n on n.oid = t.relnamespace"
                + " where n.nspname = '"
                + SCHEMA
                + "' and t.relname = '"
                + table
                + "' and tg.tgname = '"
                + trigger
                + "'")
        .isEmpty();
  }
}
