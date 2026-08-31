package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** PostgreSQL proof for the SC-1431 normalized schema and database-owned immutability guards. */
class Sc1431FlywaySchemaTest {
  private static final String SCHEMA = "sc1431_flyway_test";
  private static final String URL =
      System.getenv("SPRING_DATASOURCE_URL") == null
          ? "jdbc:postgresql://localhost:5433/chromascape_test?sslmode=disable"
          : System.getenv("SPRING_DATASOURCE_URL");
  private static final String USER =
      System.getenv("SPRING_DATASOURCE_USERNAME") == null
          ? "chromascape"
          : System.getenv("SPRING_DATASOURCE_USERNAME");
  private static final String PASSWORD =
      System.getenv("SPRING_DATASOURCE_PASSWORD") == null
          ? "chromascape123"
          : System.getenv("SPRING_DATASOURCE_PASSWORD");
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @AfterAll
  static void dropSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  @Test
  void flywayCreatesAllNormalizedTablesAndGuards() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      statement.execute("CREATE SCHEMA " + SCHEMA);
      // Flyway only applies baselineOnMigrate to a non-empty schema. The marker represents the
      // already-shipped V22 schema while keeping this test isolated from the repository's other
      // migration preconditions.
      statement.execute(
          "CREATE TABLE " + SCHEMA + ".sc1431_baseline_marker (id INTEGER PRIMARY KEY)");
    }

    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setUrl(URL);
    configuration.setUser(USER);
    configuration.setPassword(PASSWORD);
    configuration.setDefaultSchema(SCHEMA);
    configuration.setSchemas(new String[] {SCHEMA});
    configuration.setLocationsAsStrings("classpath:db/migration");
    configuration.setBaselineOnMigrate(true);
    configuration.setBaselineVersion("22");
    configuration.setTarget(MigrationVersion.fromVersion("23"));
    configuration.setOutOfOrder(true);

    assertTrue(
        new Flyway(configuration).migrate().migrations.stream()
            .anyMatch(output -> "23".equals(output.version == null ? "" : output.version)));

    Set<String> expectedTables =
        Set.of(
            "ab_orchestration",
            "ab_snapshot_binding",
            "ab_authority_consumption",
            "ab_orchestration_lease",
            "ab_orchestration_event",
            "ab_orchestration_receipt",
            "ab_orchestration_terminal_result");
    assertEquals(expectedTables, tables());
    assertTrue(indexExists("ab_orchestration", "ab_orchestration_request_id_key"));
    assertTrue(indexExists("ab_orchestration", "ab_orchestration_idempotency_key_key"));
    assertTrue(indexExists("ab_authority_consumption", "ab_authority_consumption_orchestration_id_key"));
    assertTrue(indexExists("ab_orchestration_receipt", "ab_receipt_identity_unique"));
    assertTrue(indexExists("ab_orchestration_receipt", "ab_receipt_digest_unique"));
    assertTrue(indexExists("ab_orchestration_event", "ab_event_sequence_unique"));
    assertTrue(triggerExists("ab_snapshot_binding_append_only"));
    assertTrue(triggerExists("ab_authority_consumption_append_only"));
    assertTrue(triggerExists("ab_orchestration_event_append_only"));
    assertTrue(triggerExists("ab_orchestration_receipt_append_only"));
    assertTrue(triggerExists("ab_terminal_result_write_once"));
    assertTrue(triggerExists("ab_lease_identity_immutable"));
    assertFalse(columnExists("ab_authority_consumption", "signature"));
    assertFalse(columnExists("ab_authority_consumption", "secret"));

    insertRootAndBinding();
    assertAppendOnly("ab_snapshot_binding");
  }

  private static void insertRootAndBinding() throws Exception {
    String now = Instant.parse("2026-08-31T12:00:00Z").toString();
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
      try (PreparedStatement root =
          connection.prepareStatement(
              "INSERT INTO "
                  + SCHEMA
                  + ".ab_orchestration (orchestration_id, request_id, idempotency_key, "
                  + "request_fingerprint, envelope_digest, source_type, mode, state, failure_code, "
                  + "request_json, view_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        root.setString(1, "orchestration-schema");
        root.setString(2, "request-schema");
        root.setString(3, "idempotency-schema");
        root.setString(4, DIGEST);
        root.setString(5, DIGEST);
        root.setString(6, "TEST");
        root.setString(7, "LIVE_TASK");
        root.setString(8, "CREATED");
        root.setString(9, "NONE");
        root.setString(10, "{}");
        root.setString(11, "{}");
        root.setTimestamp(12, Timestamp.from(Instant.parse(now)));
        root.setTimestamp(13, Timestamp.from(Instant.parse(now)));
        root.executeUpdate();
      }
      try (PreparedStatement binding =
          connection.prepareStatement(
              "INSERT INTO "
                  + SCHEMA
                  + ".ab_snapshot_binding (orchestration_id, decision_digest, semantic_action_digest, "
                  + "catalog_digest, capability_digest, goal_plan_digest, task_skill_digests, "
                  + "tool_schema_digest, knowledge_digest, policy_digest, verifier_digest, build_id, "
                  + "expected_observation_revision, expected_task_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        for (int index = 1; index <= 9; index++) {
          binding.setString(index, "orchestration-schema");
        }
        binding.setString(2, DIGEST);
        binding.setString(3, DIGEST);
        binding.setString(4, DIGEST);
        binding.setString(5, DIGEST);
        binding.setString(6, DIGEST);
        binding.setString(7, "[]");
        binding.setString(8, DIGEST);
        binding.setString(9, DIGEST);
        binding.setString(10, DIGEST);
        binding.setString(11, DIGEST);
        binding.setString(12, "build-v1");
        binding.setLong(13, 0);
        binding.setLong(14, 0);
        binding.executeUpdate();
      }
    }
  }

  private static void assertAppendOnly(String table) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      boolean rejected = false;
      try {
        statement.executeUpdate(
            "UPDATE " + SCHEMA + "." + table + " SET decision_digest='" + DIGEST + "'");
      } catch (SQLException expected) {
        rejected = true;
      }
      assertTrue(rejected, table + " must reject direct updates");
    }
  }

  private static Set<String> tables() throws Exception {
    Set<String> result = new HashSet<>();
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT tablename FROM pg_tables WHERE schemaname=? AND tablename LIKE 'ab_%'")) {
      query.setString(1, SCHEMA);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          result.add(rows.getString(1));
        }
      }
    }
    return result;
  }

  private static boolean indexExists(String table, String index) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE schemaname=? AND tablename=? AND indexname=?")) {
      query.setString(1, SCHEMA);
      query.setString(2, table);
      query.setString(3, index);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static boolean triggerExists(String trigger) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT 1 FROM pg_trigger WHERE tgname=? AND NOT tgisinternal")) {
      query.setString(1, trigger);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static boolean columnExists(String table, String column) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=? AND table_name=? "
                    + "AND column_name=?")) {
      query.setString(1, SCHEMA);
      query.setString(2, table);
      query.setString(3, column);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }
}
