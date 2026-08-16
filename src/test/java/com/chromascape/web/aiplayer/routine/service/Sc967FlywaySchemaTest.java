package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * SC-967 schema bootstrap proof: Flyway alone (with Hibernate ddl-auto disabled) creates a valid
 * SC-967 schema from an empty schema. This is the acceptance test for the V1_1/V4 migration
 * ownership model — the SC-967 tables must never rely on Hibernate to exist or be altered.
 *
 * <p>Runs against the configured test PostgreSQL datasource (application-test.yml, default
 * {@code localhost:5433/chromascape_test}) inside a dedicated per-run schema that is dropped
 * afterwards. Requires the local test PostgreSQL to be reachable, matching the existing
 * integration-test setup.
 */
class Sc967FlywaySchemaTest {

  private static final String SCHEMA = "sc967_flyway_test";
  private static final String UPGRADE_SCHEMA = "sc967_flyway_upgrade_test";
  private static final String URL = System.getenv("SPRING_DATASOURCE_URL") == null
      ? "jdbc:postgresql://localhost:5433/chromascape_test?sslmode=disable"
      : System.getenv("SPRING_DATASOURCE_URL");
  private static final String USER = "chromascape";
  private static final String PASSWORD = "chromascape123";

  @AfterAll
  static void dropSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      statement.execute("DROP SCHEMA IF EXISTS " + UPGRADE_SCHEMA + " CASCADE");
    }
  }

  @Test
  void flywayAloneCreatesValidSc967Schema() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
    }

    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setUrl(URL);
    configuration.setUser(USER);
    configuration.setPassword(PASSWORD);
    configuration.setDefaultSchema(SCHEMA);
    configuration.setLocationsAsStrings("classpath:db/migration");
    configuration.setBaselineOnMigrate(true);
    configuration.setBaselineVersion("0");
    configuration.setOutOfOrder(true);
    org.flywaydb.core.api.output.MigrateResult result = new Flyway(configuration).migrate();

    // V1_1 (baseline), V4 (legacy durability), and V6 (final outbox/evidence) are the SC-967
    // owners. V1_1-V5 remain immutable; the assertions below prove the additive V6 path.
    assertTrue(migratedToV6(result), "SC-967 schema must migrate through V6");

    Set<String> expectedTables = Set.of(
        "current_slice_runs", "routine_capture_records", "routine_curations",
        "behavior_graph_definitions", "routine_assistant_conversations", "routine_assistant_events",
        "routine_assistant_proposals", "routine_models", "routine_replay_traces",
        "patch_candidate_runs", "routine_capture_operations", "routine_capture_external_bindings");
    Set<String> present = tables();
    assertTrue(present.containsAll(expectedTables),
        "missing SC-967 tables; present=" + present + " expected=" + expectedTables);

    // Unique indexes required by the entity contracts.
    Map<String, String> expectedIndexes = Map.ofEntries(
        Map.entry("idx_current_slice_runs_run_id", "current_slice_runs"),
        Map.entry("idx_routine_capture_session", "routine_capture_records"),
        Map.entry("idx_routine_capture_recording", "routine_capture_records"),
        Map.entry("idx_routine_curations_recording", "routine_curations"),
        Map.entry("idx_behavior_graph_definitions_graph_id", "behavior_graph_definitions"),
        Map.entry("idx_routine_assistant_conversations_conversation_id", "routine_assistant_conversations"),
        Map.entry("idx_routine_assistant_events_cursor", "routine_assistant_events"),
        Map.entry("idx_routine_assistant_proposals_proposal_id", "routine_assistant_proposals"),
        Map.entry("idx_routine_models_model_id", "routine_models"),
        Map.entry("idx_routine_replay_traces_trace_id", "routine_replay_traces"),
        Map.entry("idx_patch_candidate_runs_proposal_id", "patch_candidate_runs"),
        Map.entry("idx_routine_capture_operations_session_kind", "routine_capture_operations"),
        Map.entry("idx_routine_capture_operations_correlation", "routine_capture_operations"),
        Map.entry("idx_routine_capture_external_binding_correlation", "routine_capture_external_bindings"));
    for (Map.Entry<String, String> index : expectedIndexes.entrySet()) {
      assertTrue(indexExists(index.getValue(), index.getKey()),
          "missing unique index " + index.getKey() + " on " + index.getValue());
      assertTrue(indexIsUnique(index.getValue(), index.getKey()),
          "index must be unique " + index.getKey());
    }
    assertTrue(indexExists("routine_capture_operations", "idx_routine_capture_operations_claim"),
        "missing capture operation claim/lease index");
    assertTrue(indexExists("routine_capture_operations", "idx_routine_capture_operations_session"),
        "missing capture operation session index");
    assertTrue(indexExists("routine_capture_external_bindings", "idx_routine_capture_external_binding_session"),
        "missing external binding lookup index");
    assertTrue(indexExists("patch_candidate_runs", "idx_patch_candidate_registration_claim"),
        "missing candidate registration lease index");

    // V4 durability columns exist for the capture outbox / assistant generation lease.
    assertTrue(columnExists("routine_capture_records", "version"), "capture version column missing");
    assertTrue(columnExists("routine_capture_records", "operation"), "capture outbox operation column missing");
    assertTrue(columnExists("routine_capture_records", "operation_state"), "capture outbox state column missing");
    assertTrue(columnExists("routine_capture_records", "operation_correlation_id"), "capture outbox correlation id missing");
    assertTrue(columnExists("routine_assistant_conversations", "generation_message"), "generation_message column missing");
    assertTrue(columnExists("routine_assistant_conversations", "generation_attempts"), "generation_attempts column missing");
    assertTrue(columnExists("patch_candidate_runs", "manifest_file_hash"), "manifest_file_hash column missing");
    assertTrue(columnExists("patch_candidate_runs", "jar_sha256"), "jar_sha256 column missing");
    assertTrue(columnExists("patch_candidate_runs", "certification_document_hash"), "certification_document_hash column missing");
    assertTrue(columnExists("patch_candidate_runs", "registry_response_bytes"), "registry_response_bytes column missing");
    assertTrue(columnExists("patch_candidate_runs", "registry_response_hash"), "registry_response_hash column missing");
    assertTrue(columnExists("patch_candidate_runs", "registry_manifest_hash"), "registry_manifest_hash column missing");
    assertTrue(columnExists("patch_candidate_runs", "registry_download_hash"), "registry_download_hash column missing");
    assertTrue(columnExists("patch_candidate_runs", "registry_verified_at"), "registry_verified_at column missing");
    assertTrue(columnExists("patch_candidate_runs", "registration_lease_expires_at"),
        "registration lease column missing");

    assertTrue(columnExists("routine_capture_operations", "lease_expires_at"), "capture operation lease missing");
    assertTrue(columnExists("routine_capture_operations", "attempt_id"), "capture operation attempt missing");
    assertTrue(columnExists("routine_capture_operations", "next_attempt_at"), "capture operation recovery time missing");
    assertTrue(columnExists("routine_capture_external_bindings", "correlation_id"), "external correlation binding missing");
    assertTrue(columnExists("routine_capture_external_bindings", "completion_attempted"), "external completion binding missing");
  }

  @Test
  void v5UpgradeBackfillsUnfinishedCaptureOperationsWithUniqueCorrelations() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS " + UPGRADE_SCHEMA);
    }

    ClassicConfiguration v5 = configuration(UPGRADE_SCHEMA);
    v5.setTarget(org.flywaydb.core.api.MigrationVersion.fromVersion("5"));
    new Flyway(v5).migrate();

    String hash = "sha256:" + "0".repeat(64);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO " + UPGRADE_SCHEMA + ".routine_capture_records "
          + "(session_id, start_idempotency_key, start_request_hash, identity_json, started_at, "
          + "start_tick, ticks_json, actions_json, tick_sequences_json, action_sequences_json, "
          + "tick_idempotency_json, action_idempotency_json, request_idempotency_json, "
          + "next_tick_sequence, next_action_sequence, completed, completion_status, "
          + "operation_correlation_id) VALUES "
          + "('legacy-start', 'legacy-start-key', '" + hash + "', '{}', CURRENT_TIMESTAMP, 0, "
          + "'[]', '[]', '{}', '{}', '{}', '{}', '{}', 1, 1, FALSE, 'STARTING', "
          + "'legacy-reused-correlation')");
      statement.execute("INSERT INTO " + UPGRADE_SCHEMA + ".routine_capture_records "
          + "(session_id, start_idempotency_key, start_request_hash, identity_json, started_at, "
          + "start_tick, ticks_json, actions_json, tick_sequences_json, action_sequences_json, "
          + "tick_idempotency_json, action_idempotency_json, request_idempotency_json, "
          + "next_tick_sequence, next_action_sequence, completed, completion_status, recording_id, "
          + "completion_request_hash, operation_correlation_id) VALUES "
          + "('legacy-complete', 'legacy-complete-key', '" + hash + "', '{}', CURRENT_TIMESTAMP, 0, "
          + "'[]', '[]', '{}', '{}', '{}', '{}', '{}', 1, 1, FALSE, 'COMPLETING', "
          + "'recording-legacy', '" + hash + "', 'legacy-reused-correlation')");
    }

    ClassicConfiguration v6 = configuration(UPGRADE_SCHEMA);
    org.flywaydb.core.api.output.MigrateResult result = new Flyway(v6).migrate();
    assertTrue(migratedToV6(result), "upgrade path must apply V6");
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet operations = statement.executeQuery(
            "select count(*), count(distinct correlation_id) from "
                + UPGRADE_SCHEMA + ".routine_capture_operations")) {
      assertTrue(operations.next());
      assertEquals(2, operations.getInt(1), "both unfinished legacy phases must be backfilled");
      assertEquals(2, operations.getInt(2), "backfilled correlation ids must remain unique");
    }
  }

  private static ClassicConfiguration configuration(String schema) {
    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setUrl(URL);
    configuration.setUser(USER);
    configuration.setPassword(PASSWORD);
    configuration.setDefaultSchema(schema);
    configuration.setLocationsAsStrings("classpath:db/migration");
    configuration.setBaselineOnMigrate(true);
    configuration.setBaselineVersion("0");
    configuration.setOutOfOrder(true);
    return configuration;
  }

  private boolean migratedToV6(org.flywaydb.core.api.output.MigrateResult result) {
    for (org.flywaydb.core.api.output.MigrateOutput output : result.migrations) {
      if ("6".equals(output.version == null ? "" : output.version)) {
        return true;
      }
    }
    return false;
  }

  private List<String> query(String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        ResultSet result = connection.createStatement().executeQuery(sql)) {
      while (result.next()) {
        rows.add(result.getString(1));
      }
    }
    return rows;
  }

  private Set<String> tables() throws Exception {
    return Set.copyOf(query("select tablename from pg_tables where schemaname = '" + SCHEMA + "'"));
  }

  private boolean indexExists(String table, String index) throws Exception {
    return !query("select indexname from pg_indexes where schemaname = '" + SCHEMA
        + "' and tablename = '" + table + "' and indexname = '" + index + "'").isEmpty();
  }

  private boolean indexIsUnique(String table, String index) throws Exception {
    return query("select indexdef from pg_indexes where schemaname = '" + SCHEMA
        + "' and tablename = '" + table + "' and indexname = '" + index
        + "' and indexdef like 'CREATE UNIQUE INDEX%'").size() == 1;
  }

  private boolean columnExists(String table, String column) throws Exception {
    return !query("select column_name from information_schema.columns where table_schema = '"
        + SCHEMA + "' and table_name = '" + table + "' and column_name = '" + column + "'").isEmpty();
  }
}
