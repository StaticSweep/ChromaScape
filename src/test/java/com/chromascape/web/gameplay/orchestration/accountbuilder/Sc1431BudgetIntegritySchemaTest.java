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
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Direct Flyway/PostgreSQL proof of the SC-1431 budget contract.
 *
 * <p>The test stages the full repository migration chain, proves the upgrade to {@code V89}, and
 * asserts the database-owned constraints: non-null timestamps and limits, bound and counter checks,
 * expiry ordering, immutable claimed limits and timestamps, monotonic counters and an advancing
 * optimistic-lock version. It runs against an isolated schema on the local PostgreSQL instance and
 * never touches a developer database.
 */
class Sc1431BudgetIntegritySchemaTest {
  private static final String SCHEMA = "sc1431_budget_integrity_test";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
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
  private static final Instant STARTED = Instant.parse("2026-08-31T12:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-08-31T12:10:00Z");

  @AfterAll
  static void dropSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  @Test
  void v59UpgradeAndBudgetConstraintsAreDatabaseOwned() throws Exception {
    recreateSchema();
    // The previous integration head applies cleanly, then the SC-1498 V89 upgrade applies on top.
    migrate("88");
    assertEquals("88", currentVersion());
    migrate(null);

    assertEquals("90", currentVersion());
    assertFalse(columnIsNullable("ab_orchestration_budget", "started_at"));
    assertFalse(columnIsNullable("ab_orchestration_budget", "expires_at"));
    assertFalse(columnIsNullable("ab_orchestration_budget", "max_actions"));
    assertFalse(columnIsNullable("ab_orchestration_budget", "max_attempts"));
    assertFalse(columnIsNullable("ab_orchestration_budget", "max_elapsed_ms"));
    assertTrue(triggerExists("ab_budget_guard_trigger"));

    // V60: bootstrap prompt provenance is nullable while provider session/turn identity is present.
    assertTrue(columnIsNullable("ab_snapshot_binding", "prompt_digest"));
    assertTrue(columnIsNullable("ab_snapshot_binding", "provider_session_id"));
    assertTrue(columnIsNullable("ab_snapshot_binding", "provider_turn_id"));

    // A valid budget row at the exact contract bounds is accepted, at both the minimum and the
    // maximum of every bound.
    execute(insertBudget("orchestration-budget-min", 1, 1, 1_000L));
    execute(insertBudget("orchestration-budget-max", 20, 3, 1_800_000L));

    // Bounds reject invalid limits.
    reject(insertBudget("orchestration-low-actions", 0, 1, 1_000L));
    reject(insertBudget("orchestration-high-actions", 21, 1, 1_000L));
    reject(insertBudget("orchestration-low-attempts", 1, 0, 1_000L));
    reject(insertBudget("orchestration-high-attempts", 1, 4, 1_000L));
    reject(insertBudget("orchestration-low-elapsed", 1, 1, 999L));
    reject(insertBudget("orchestration-high-elapsed", 1, 1, 1_800_001L));

    // Null or unordered timestamps are rejected.
    reject(insertBudgetWithTimes("orchestration-null-start", 1, 1, 1_000L, null, EXPIRES));
    reject(insertBudgetWithTimes("orchestration-unordered", 1, 1, 1_000L, EXPIRES, STARTED));

    // Claimed limits and timestamps are immutable.
    reject(updateBudget("orchestration-budget-min", "max_actions", "2"));
    reject(updateBudget("orchestration-budget-min", "max_attempts", "2"));
    reject(updateBudget("orchestration-budget-min", "max_elapsed_ms", "2000"));
    reject(updateBudget("orchestration-budget-min", "started_at", "now()"));
    reject(updateBudget("orchestration-budget-min", "expires_at", "now() + interval '1 hour'"));
    reject(updateBudget("orchestration-budget-min", "orchestration_id", "'moved'"));

    // Counters may only advance and the optimistic-lock version must advance with them.
    reject(updateBudget("orchestration-budget-min", "actions_used", "actions_used - 1"));
    reject(updateBudget("orchestration-budget-min", "attempts_used", "attempts_used - 1"));
    reject(
        "UPDATE %n.ab_orchestration_budget SET actions_used = actions_used + 1"
            + " WHERE orchestration_id = 'orchestration-budget-min'");

    // A valid atomic counter advance succeeds.
    execute(
        "UPDATE %n.ab_orchestration_budget SET actions_used = actions_used + 1, version = version"
            + " + 1 WHERE orchestration_id = 'orchestration-budget-min'");

    // Counters can never exceed their limits or go negative, and neither can the version.
    reject(updateBudget("orchestration-budget-min", "actions_used", "max_actions + 1"));
    reject(updateBudget("orchestration-budget-max", "attempts_used", "max_attempts + 1"));
    reject(updateBudget("orchestration-budget-min", "actions_used", "-1"));
    reject(updateBudget("orchestration-budget-min", "version", "-1"));

    // Deletion stays restricted through the parent relationship.
    reject("DELETE FROM %n.ab_orchestration WHERE orchestration_id = 'orchestration-budget-min'");
  }

  private static String insertBudget(
      String orchestrationId, int maxActions, int maxAttempts, long maxElapsedMs) {
    return insertBudgetWithTimes(
        orchestrationId, maxActions, maxAttempts, maxElapsedMs, STARTED, EXPIRES);
  }

  private static String insertBudgetWithTimes(
      String orchestrationId,
      int maxActions,
      int maxAttempts,
      long maxElapsedMs,
      Instant startedAt,
      Instant expiresAt) {
    seedOrchestration(orchestrationId);
    return "INSERT INTO %n.ab_orchestration_budget (orchestration_id, max_actions, max_attempts,"
        + " max_elapsed_ms, actions_used, attempts_used, started_at, expires_at, version) VALUES"
        + " ('"
        + orchestrationId
        + "', "
        + maxActions
        + ", "
        + maxAttempts
        + ", "
        + maxElapsedMs
        + ", 0, 0, "
        + (startedAt == null ? "NULL" : "'" + startedAt + "'::timestamptz")
        + ", '"
        + expiresAt
        + "'::timestamptz, 0)";
  }

  private static String updateBudget(String orchestrationId, String column, String expression) {
    return "UPDATE %n.ab_orchestration_budget SET "
        + column
        + " = "
        + expression
        + ", version = version + 1 WHERE orchestration_id = '"
        + orchestrationId
        + "'";
  }

  private static void seedOrchestration(String orchestrationId) {
    String sql =
        "INSERT INTO %n.ab_orchestration (orchestration_id, request_id, idempotency_key,"
            + " request_fingerprint, envelope_digest, source_type, mode, state, failure_code,"
            + " request_json, view_json, retry_allowed, view_version, created_at, updated_at,"
            + " entity_version) VALUES ('"
            + orchestrationId
            + "', 'request-"
            + orchestrationId
            + "', 'idempotency-"
            + orchestrationId
            + "', '"
            + DIGEST
            + "', '"
            + DIGEST
            + "', 'TEST', 'LIVE_TASK', 'CREATED', 'NONE', '{}', '{}', FALSE, 0, '"
            + STARTED
            + "'::timestamptz, '"
            + STARTED
            + "'::timestamptz, 0)";
    try {
      execute(sql);
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String currentVersion() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT version FROM "
                    + SCHEMA
                    + ".flyway_schema_history WHERE success = TRUE"
                    + " ORDER BY installed_rank DESC LIMIT 1")) {
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static boolean columnIsNullable(String table, String column) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = ?"
                    + " AND table_name = ? AND column_name = ?")) {
      query.setString(1, SCHEMA);
      query.setString(2, table);
      query.setString(3, column);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() && "YES".equals(rows.getString(1));
      }
    }
  }

  private static boolean triggerExists(String trigger) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE t.tgname = ? AND n.nspname = ? AND NOT t.tgisinternal")) {
      query.setString(1, trigger);
      query.setString(2, SCHEMA);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static void recreateSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      statement.execute("CREATE SCHEMA " + SCHEMA);
    }
  }

  private static void migrate(String target) {
    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setUrl(URL);
    configuration.setUser(USER);
    configuration.setPassword(PASSWORD);
    configuration.setDefaultSchema(SCHEMA);
    configuration.setSchemas(new String[] {SCHEMA});
    configuration.setLocationsAsStrings("classpath:db/migration");
    if (target != null) {
      configuration.setTarget(MigrationVersion.fromVersion(target));
    }
    assertTrue(new Flyway(configuration).migrate().success);
  }

  private static void execute(String sqlWithSchemaToken) throws SQLException {
    String sql = sqlWithSchemaToken.replace("%n", SCHEMA);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void reject(String sqlWithSchemaToken) throws Exception {
    String sql = sqlWithSchemaToken.replace("%n", SCHEMA);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      try {
        statement.execute(sql);
        throw new AssertionError("Database must reject: " + sql);
      } catch (SQLException expected) {
        // The database owns the contract; a rejected statement is the required outcome.
      }
    }
  }
}
