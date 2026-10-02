package com.chromascape.web.gameplay.orchestration.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * SC-1449 migration proof.
 *
 * <p>Three things are proven here, each in its own dedicated schema so the tests never share state:
 *
 * <ul>
 *   <li>Flyway alone builds the complete v2 gate table: V40 creates it, V41 adds the active-slot
 *       partial unique index, V42 adds the expiry columns and makes them {@code NOT NULL}.
 *   <li>A database that stopped at the original V40 — the shipped V40, which has never carried the
 *       expiry columns — migrates forward through V41/V42 with its existing rows intact. A row
 *       whose canonical request bytes are parseable is backfilled with its exact persisted deadline
 *       and authority expiry.
 *   <li>A row whose canonical bytes cannot be reconstructed is moved to {@code
 *       NEEDS_RECONCILIATION} with epoch timestamps, so the start-time checks fail closed instead
 *       of skipping a null and failing open.
 * </ul>
 *
 * <p>Runs against the configured test PostgreSQL datasource (application-test.yml, default {@code
 * localhost:5433/chromascape_test}). Requires the local test PostgreSQL to be reachable, matching
 * the existing integration-test setup.
 */
class GameplayV2GateMigrationIntegrationTest {

  private static final String SCHEMA = "sc1449_gate_migration_test";
  private static final String LEGACY_SCHEMA = "sc1449_gate_migration_legacy_v40";
  private static final String SECOND_SCHEMA = "sc1449_gate_migration_second";
  private static final String URL =
      System.getenv("SPRING_DATASOURCE_URL") == null
          ? "jdbc:postgresql://localhost:5433/chromascape_test?sslmode=disable"
          : System.getenv("SPRING_DATASOURCE_URL");
  private static final String USER = "chromascape";
  private static final String PASSWORD = "chromascape123";

  @AfterAll
  static void dropSchemas() throws Exception {
    for (String schema : List.of(SCHEMA, LEGACY_SCHEMA, SECOND_SCHEMA)) {
      execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void v40V41V42BuildTheCompleteGateTable() throws Exception {
    createSchema(SCHEMA);
    MigrateResult result = new Flyway(configuration(SCHEMA)).migrate();

    assertTrue(migratedToVersion(result, "40"), "V40 must be applied");
    assertTrue(migratedToVersion(result, "41"), "V41 must be applied");
    assertTrue(migratedToVersion(result, "42"), "V42 must be applied");

    assertTrue(columnExists(SCHEMA, "request_bytes"), "V40 must define the canonical bytes");
    assertTrue(columnExists(SCHEMA, "state"), "V40 must define state");
    assertTrue(columnExists(SCHEMA, "failure_code"), "V40 must define failure_code");

    assertTrue(columnExists(SCHEMA, "envelope_deadline"), "V42 must add envelope_deadline");
    assertTrue(columnExists(SCHEMA, "authority_expires_at"), "V42 must add authority_expires_at");
    assertTrue(columnIsNotNull(SCHEMA, "envelope_deadline"), "envelope_deadline must be NOT NULL");
    assertTrue(
        columnIsNotNull(SCHEMA, "authority_expires_at"), "authority_expires_at must be NOT NULL");

    assertTrue(
        constraintExists(SCHEMA, "uq_gameplay_v2_gate_idempotency"),
        "idempotency key must be unique");
    assertTrue(
        constraintExists(SCHEMA, "uq_gameplay_v2_gate_authority"), "authority id must be unique");

    assertTrue(
        indexExists(SCHEMA, "uq_gameplay_v2_gate_active_slot"),
        "V41 must create the active-slot partial unique index");
    assertTrue(
        indexIsPartial(SCHEMA, "uq_gameplay_v2_gate_active_slot"),
        "active-slot index must be partial (WHERE non-terminal)");
  }

  @Test
  void originalV40DatabaseMigratesForwardAndBackfillsFromCanonicalBytes() throws Exception {
    createSchema(LEGACY_SCHEMA);

    // Stop at the original V40: the table exists with no expiry columns at all.
    ClassicConfiguration atV40 = configuration(LEGACY_SCHEMA);
    atV40.setTarget(MigrationVersion.fromVersion("40"));
    new Flyway(atV40).migrate();
    assertTrue(columnExists(LEGACY_SCHEMA, "request_id"), "V40 must have created the table");
    assertTrue(
        !columnExists(LEGACY_SCHEMA, "envelope_deadline"),
        "the original V40 must not define envelope_deadline");

    Instant deadline = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    Instant expiry = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    insertGateRow(
        LEGACY_SCHEMA, "req-reconstructable", "RESERVED", canonicalBundleBytes(deadline, expiry));
    insertGateRow(
        LEGACY_SCHEMA,
        "req-unreconstructable",
        "RESERVED",
        "not canonical json at all".getBytes(StandardCharsets.UTF_8));
    insertGateRow(
        LEGACY_SCHEMA,
        "req-terminal",
        "STOPPED",
        "also not canonical json".getBytes(StandardCharsets.UTF_8));

    // Now migrate the rest. V40's checksum is untouched, so validation passes and V41/V42 run.
    MigrateResult forward = new Flyway(configuration(LEGACY_SCHEMA)).migrate();
    assertTrue(migratedToVersion(forward, "41"), "V41 must apply over an original-V40 database");
    assertTrue(migratedToVersion(forward, "42"), "V42 must apply over an original-V40 database");

    assertEquals(
        deadline,
        deadlineOf(LEGACY_SCHEMA, "req-reconstructable"),
        "a parseable row keeps its exact persisted deadline");
    assertEquals(
        expiry,
        authorityExpiryOf(LEGACY_SCHEMA, "req-reconstructable"),
        "a parseable row keeps its exact persisted authority expiry");
    assertEquals(
        "RESERVED",
        stateOf(LEGACY_SCHEMA, "req-reconstructable"),
        "a reconstructed row keeps its state");

    assertEquals(
        Instant.EPOCH,
        deadlineOf(LEGACY_SCHEMA, "req-unreconstructable"),
        "an unreconstructable row must be already expired, never open-ended");
    assertEquals(
        Instant.EPOCH,
        authorityExpiryOf(LEGACY_SCHEMA, "req-unreconstructable"),
        "an unreconstructable authority must be already expired");
    assertEquals(
        "NEEDS_RECONCILIATION",
        stateOf(LEGACY_SCHEMA, "req-unreconstructable"),
        "an unreconstructable non-terminal row must become operator-visible");

    assertEquals(
        "STOPPED",
        stateOf(LEGACY_SCHEMA, "req-terminal"),
        "a proven terminal row is never walked backward");

    assertTrue(
        columnIsNotNull(LEGACY_SCHEMA, "envelope_deadline"),
        "NOT NULL must be applied after the backfill");
    assertTrue(
        columnIsNotNull(LEGACY_SCHEMA, "authority_expires_at"),
        "NOT NULL must be applied after the backfill");
  }

  @Test
  void migrationsApplyCleanlyInAnIndependentSchema() throws Exception {
    createSchema(SECOND_SCHEMA);
    MigrateResult result = new Flyway(configuration(SECOND_SCHEMA)).migrate();
    assertTrue(migratedToVersion(result, "42"), "V42 must apply in a second schema");
    assertTrue(
        columnIsNotNull(SECOND_SCHEMA, "envelope_deadline"),
        "the second schema must reach the same final shape");
  }

  /**
   * The canonical bundle bytes as ChromaScape persists them: the complete snapshot object whose
   * {@code executionEnvelope.deadline} and {@code authority.expiresAt} are the validity window V42
   * reconstructs from.
   */
  private static byte[] canonicalBundleBytes(Instant deadline, Instant authorityExpiresAt) {
    String json =
        "{\"executionEnvelope\":{\"requestId\":\"req\",\"deadline\":\""
            + deadline
            + "\"},\"authority\":{\"authorityId\":\"auth\",\"expiresAt\":\""
            + authorityExpiresAt
            + "\"}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static ClassicConfiguration configuration(String schema) {
    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setUrl(URL);
    configuration.setUser(USER);
    configuration.setPassword(PASSWORD);
    configuration.setDefaultSchema(schema);
    configuration.setSchemas(new String[] {schema});
    configuration.setLocationsAsStrings("classpath:db/migration");
    configuration.setBaselineOnMigrate(true);
    configuration.setBaselineVersion("0");
    configuration.setOutOfOrder(true);
    return configuration;
  }

  private static void createSchema(String schema) throws Exception {
    execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    execute("CREATE SCHEMA " + schema);
  }

  private static void insertGateRow(String schema, String requestId, String state, byte[] bytes)
      throws Exception {
    String sql =
        "insert into "
            + schema
            + ".gameplay_v2_gate_request (request_id, idempotency_key, fingerprint, "
            + "envelope_digest, authority_id, account_id, client_id, lease_generation, fence, "
            + "state, acknowledged, request_bytes, evidence_refs, entity_version, created_at, "
            + "updated_at) values (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, false, ?, '', 0, now(), now())";
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, requestId);
      statement.setString(2, "idem-" + requestId);
      statement.setString(3, "fingerprint-" + requestId);
      statement.setString(4, "digest-" + requestId);
      statement.setString(5, "authority-" + requestId);
      statement.setString(6, "account-" + requestId);
      statement.setString(7, "client-" + requestId);
      statement.setString(8, state);
      statement.setBytes(9, bytes);
      statement.executeUpdate();
    }
  }

  private static boolean migratedToVersion(MigrateResult result, String version) {
    for (MigrateOutput output : result.migrations) {
      if (version.equals(output.version == null ? "" : output.version)) {
        return true;
      }
    }
    return false;
  }

  private static boolean columnExists(String schema, String column) throws Exception {
    return !query(
            "select column_name from information_schema.columns where table_schema = '"
                + schema
                + "' and table_name = 'gameplay_v2_gate_request' and column_name = '"
                + column
                + "'")
        .isEmpty();
  }

  private static boolean columnIsNotNull(String schema, String column) throws Exception {
    return query(
            "select is_nullable from information_schema.columns where table_schema = '"
                + schema
                + "' and table_name = 'gameplay_v2_gate_request' and column_name = '"
                + column
                + "'")
        .equals(List.of("NO"));
  }

  private static boolean constraintExists(String schema, String constraint) throws Exception {
    return !query(
            "select constraint_name from information_schema.table_constraints where table_schema"
                + " = '"
                + schema
                + "' and constraint_name = '"
                + constraint
                + "'")
        .isEmpty();
  }

  private static boolean indexExists(String schema, String index) throws Exception {
    return !query(
            "select indexname from pg_indexes where schemaname = '"
                + schema
                + "' and indexname = '"
                + index
                + "'")
        .isEmpty();
  }

  private static boolean indexIsPartial(String schema, String index) throws Exception {
    return query(
                "select indexdef from pg_indexes where schemaname = '"
                    + schema
                    + "' and indexname = '"
                    + index
                    + "' and indexdef like '%WHERE%'")
            .size()
        == 1;
  }

  private static String stateOf(String schema, String requestId) throws Exception {
    return single(
        "select state from "
            + schema
            + ".gameplay_v2_gate_request where request_id = '"
            + requestId
            + "'");
  }

  private static Instant deadlineOf(String schema, String requestId) throws Exception {
    return instantOf(schema, requestId, "envelope_deadline");
  }

  private static Instant authorityExpiryOf(String schema, String requestId) throws Exception {
    return instantOf(schema, requestId, "authority_expires_at");
  }

  private static Instant instantOf(String schema, String requestId, String column)
      throws Exception {
    String sql =
        "select " + column + " from " + schema + ".gameplay_v2_gate_request where request_id = ?";
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, requestId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? result.getObject(1, java.time.OffsetDateTime.class).toInstant()
            : null;
      }
    }
  }

  private static String single(String sql) throws Exception {
    List<String> rows = query(sql);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static void execute(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<String> query(String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        rows.add(result.getString(1));
      }
    }
    return rows;
  }
}
