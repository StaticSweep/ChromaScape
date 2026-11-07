package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

/** Proves the SC-1430 migration on an empty and a pre-existing isolated PostgreSQL schema. */
class Sc1430FlywaySchemaTest {
  /** The SC-1430 migration is V22; later migrations are outside this proof's scope. */
  private static final int TARGET_VERSION = 22;

  private final IsolatedPostgresTestDatabase.Database database =
      IsolatedPostgresTestDatabase.newDatabase();

  private static final List<String> TABLES =
      List.of(
          "gameplay_provider_session",
          "gameplay_provider_budget_ledger",
          "gameplay_provider_turn",
          "gameplay_provider_event",
          "gameplay_provider_tool_call",
          "gameplay_provider_decision",
          "gameplay_provider_transcript_artifact");

  @Test
  void migratesEmptySchemaAndCreatesProviderRuntimeTables() throws SQLException {
    String schema = freshSchema(database.schema());
    Flyway flyway = configuration(schema).load();
    flyway.migrate();

    assertEquals(
        MigrationVersion.fromVersion(String.valueOf(TARGET_VERSION)),
        flyway.info().current().getVersion());
    try (Connection connection = connect()) {
      for (String table : TABLES) {
        assertTrue(tableExists(connection, schema, table), "missing table " + table);
      }
      assertTrue(columnExists(connection, schema, "gameplay_provider_session", "decide_only"));
      assertTrue(columnExists(connection, schema, "gameplay_provider_session", "version"));
      assertTrue(columnExists(connection, schema, "gameplay_provider_turn", "request_id"));
      assertTrue(columnExists(connection, schema, "gameplay_provider_turn", "idempotency_key"));
      assertTrue(columnExists(connection, schema, "gameplay_provider_event", "sequence_no"));
      assertTrue(
          columnExists(connection, schema, "gameplay_provider_transcript_artifact", "sha256"));
      assertTrue(hasCheckConstraint(connection, schema, "gameplay_provider_session"));
      assertTrue(hasUniqueIndex(connection, schema, "gameplay_provider_turn"));
      assertTrue(hasRecoveryIndex(connection, schema));
    }
  }

  @Test
  void appliesProviderRuntimeMigrationOnTopOfExistingHistory() throws SQLException {
    String schema = freshSchema(database.schema());
    configuration(schema).target("5").load().migrate();
    try (Connection connection = connect()) {
      assertFalse(
          tableExists(connection, schema, "gameplay_provider_turn"),
          "provider runtime table must not exist before V22");
    }

    configuration(schema).load().migrate();
    try (Connection connection = connect()) {
      for (String table : TABLES) {
        assertTrue(tableExists(connection, schema, table), "missing table " + table);
      }
    }
  }

  private FluentConfiguration configuration(String schema) {
    return Flyway.configure()
        .dataSource(database.jdbcUrl(), database.username(), database.password())
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target(String.valueOf(TARGET_VERSION))
        .cleanDisabled(false);
  }

  private String freshSchema(String schema) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists \"" + schema + "\" cascade");
      statement.execute("create schema \"" + schema + "\"");
    }
    return schema;
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(
        database.jdbcUrl(), database.username(), database.password());
  }

  private boolean tableExists(Connection connection, String schema, String table)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select 1 from information_schema.tables where table_schema=? and table_name=?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (var result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean columnExists(Connection connection, String schema, String table, String column)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select 1 from information_schema.columns"
                + " where table_schema=? and table_name=? and column_name=?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (var result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean hasCheckConstraint(Connection connection, String schema, String table)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select 1 from information_schema.table_constraints"
                + " where table_schema=? and table_name=? and constraint_type='CHECK'")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (var result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean hasUniqueIndex(Connection connection, String schema, String table)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select 1 from pg_indexes where schemaname=? and tablename=? and indexdef ilike"
                + " '%unique%'")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (var result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean hasRecoveryIndex(Connection connection, String schema) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select 1 from pg_indexes where schemaname=? and indexname=?")) {
      statement.setString(1, schema);
      statement.setString(2, "idx_gameplay_provider_turn_recovery");
      try (var result = statement.executeQuery()) {
        return result.next();
      }
    }
  }
}
