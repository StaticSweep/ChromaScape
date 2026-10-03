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
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL coverage for the SC-1618 v1-to-v2 packet identity migration. */
@Testcontainers
class PacketObservationMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("packet_observations")
          .withUsername("test")
          .withPassword("test");

  private String schema;

  @BeforeEach
  void createSchema() throws Exception {
    schema = "packet_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA \"" + schema + "\"");
    }
  }

  @AfterEach
  void dropSchema() throws Exception {
    if (schema == null) {
      return;
    }
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
    } finally {
      schema = null;
    }
  }

  @Test
  void cleanMigrationEnforcesV2IdentityAndRetryUniqueness() throws Exception {
    try (Connection connection = schemaConnection()) {
      createDebugSessionsTable(connection);
      migrate(
          "V39__sc1618_packet_observations.sql",
          "V43__sc1618_packet_observation_identity_v2.sql",
          "V44__sc1618_packet_observation_identity_constraints.sql");
      insertSession(connection, "clean-session");

      insertV2(connection, "clean-session", "source-a", 7L, 4812L, "DECODED", false);
      insertV2(connection, "clean-session", "source-a", 8L, null, "GAP", true);
      // Observation identity is source-scoped; the same observation number on another source is
      // valid even when capture sequences overlap.
      insertV2(connection, "clean-session", "source-b", 7L, 4812L, "DECODED", false);
      assertEquals(3, countRows(connection, "clean-session"));

      // The repository's lost-ACK retry uses the same identity tuple and must not duplicate it.
      insertV2OnConflictDoNothing(
          connection, "clean-session", "source-a", 7L, 4812L, "DECODED", false);
      assertEquals(3, countRows(connection, "clean-session"));

      // Reusing the identity with altered content is a permanent database conflict.
      assertThrows(
          SQLException.class,
          () -> insertV2(connection, "clean-session", "source-a", 7L, 4813L, "DECODED", false));

      // V2 real observations require a capture identity.
      assertThrows(
          SQLException.class,
          () -> insertV2(connection, "clean-session", "source-a", 9L, null, "DECODED", false));
      // V2 GAP observations must omit capture identity and be synthetic.
      assertThrows(
          SQLException.class,
          () -> insertV2(connection, "clean-session", "source-a", 9L, 4814L, "GAP", true));
      assertThrows(
          SQLException.class,
          () -> insertV2(connection, "clean-session", "source-a", 9L, null, "GAP", false));
    }
  }

  @Test
  void v43BackfillsAppliedV1RowsBeforeV44EnforcesTheNewContract() throws Exception {
    try (Connection connection = schemaConnection()) {
      createDebugSessionsTable(connection);
      migrate("V39__sc1618_packet_observations.sql");
      insertSession(connection, "upgrade-session");
      insertV1BeforeV43(connection, "upgrade-session", "legacy-source", 4812L);

      migrate(
          "V39__sc1618_packet_observations.sql",
          "V43__sc1618_packet_observation_identity_v2.sql",
          "V44__sc1618_packet_observation_identity_constraints.sql");

      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT packet_schema, observation_sequence, packet_sequence "
                      + "FROM packet_observations WHERE session_id = 'upgrade-session'")) {
        assertTrue(result.next());
        assertEquals("rsprox-packet/v1", result.getString("packet_schema"));
        assertEquals(4812L, result.getLong("observation_sequence"));
        assertEquals(4812L, result.getLong("packet_sequence"));
      }
    }
  }

  @Test
  void v46NormalizesPacketSessionForeignKeyToCascade() throws Exception {
    try (Connection connection = schemaConnection()) {
      createDebugSessionsTable(connection);
      migrate(
          "V39__sc1618_packet_observations.sql",
          "V43__sc1618_packet_observation_identity_v2.sql",
          "V44__sc1618_packet_observation_identity_constraints.sql",
          "V45__schema_scoped_replay_foreign_keys.sql",
          "V46__sc1618_packet_observation_fk_cascade.sql");
      insertSession(connection, "cascade-session");
      insertV2(connection, "cascade-session", "source-a", 1L, 4812L, "DECODED", false);

      try (var statement =
          connection.prepareStatement(
              "SELECT delete_rule FROM information_schema.referential_constraints "
                  + "WHERE constraint_schema = ? AND constraint_name = ?")) {
        statement.setString(1, schema);
        statement.setString(2, "fk_packet_observations_session");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals("CASCADE", result.getString("delete_rule"));
        }
      }

      execute(connection, "DELETE FROM debug_sessions WHERE session_id = 'cascade-session'");
      assertEquals(0, countRows(connection, "cascade-session"));
    }
  }

  private Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private Connection schemaConnection() throws SQLException {
    Connection connection = adminConnection();
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO \"" + schema + "\"");
    }
    return connection;
  }

  private void createDebugSessionsTable(Connection connection) throws SQLException {
    execute(connection, "CREATE TABLE debug_sessions (session_id varchar(100) PRIMARY KEY)");
  }

  private void insertSession(Connection connection, String sessionId) throws SQLException {
    try (var statement =
        connection.prepareStatement("INSERT INTO debug_sessions(session_id) VALUES (?)")) {
      statement.setString(1, sessionId);
      statement.executeUpdate();
    }
  }

  private void insertV1BeforeV43(
      Connection connection, String sessionId, String sourceSessionId, long sequence)
      throws SQLException {
    String sql =
        "INSERT INTO packet_observations "
            + "(session_id, tick, source_session_id, packet_sequence, captured_at, "
            + "capture_delta_millis, direction, opcode, protocol_name, encoded_size, "
            + "decoded_type, decode_status, synthetic) "
            + "VALUES (?, 100, ?, ?, CURRENT_TIMESTAMP, 0, 'SERVER_TO_CLIENT', 1, "
            + "'LEGACY', 1, 'Legacy', 'DECODED', FALSE)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, sessionId);
      statement.setString(2, sourceSessionId);
      statement.setLong(3, sequence);
      statement.executeUpdate();
    }
  }

  private void insertV2(
      Connection connection,
      String sessionId,
      String sourceSessionId,
      long observationSequence,
      Long packetSequence,
      String decodeStatus,
      boolean synthetic)
      throws SQLException {
    String sql =
        "INSERT INTO packet_observations "
            + "(session_id, tick, source_session_id, packet_schema, observation_sequence, "
            + "packet_sequence, captured_at, capture_delta_millis, direction, opcode, "
            + "protocol_name, encoded_size, decoded_type, decode_status, synthetic) "
            + "VALUES (?, 100, ?, 'rsprox-packet/v2', ?, ?, CURRENT_TIMESTAMP, 0, "
            + "'SERVER_TO_CLIENT', 1, 'PACKET', 1, 'Packet', ?, ?)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, sessionId);
      statement.setString(2, sourceSessionId);
      statement.setLong(3, observationSequence);
      if (packetSequence == null) {
        statement.setObject(4, null);
      } else {
        statement.setLong(4, packetSequence);
      }
      statement.setString(5, decodeStatus);
      statement.setBoolean(6, synthetic);
      statement.executeUpdate();
    }
  }

  private void insertV2OnConflictDoNothing(
      Connection connection,
      String sessionId,
      String sourceSessionId,
      long observationSequence,
      Long packetSequence,
      String decodeStatus,
      boolean synthetic)
      throws SQLException {
    String sql =
        "INSERT INTO packet_observations "
            + "(session_id, tick, source_session_id, packet_schema, observation_sequence, "
            + "packet_sequence, captured_at, capture_delta_millis, direction, opcode, "
            + "protocol_name, encoded_size, decoded_type, decode_status, synthetic) "
            + "VALUES (?, 100, ?, 'rsprox-packet/v2', ?, ?, CURRENT_TIMESTAMP, 0, "
            + "'SERVER_TO_CLIENT', 1, 'PACKET', 1, 'Packet', ?, ?) "
            + "ON CONFLICT (session_id, source_session_id, packet_schema, observation_sequence) "
            + "DO NOTHING";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, sessionId);
      statement.setString(2, sourceSessionId);
      statement.setLong(3, observationSequence);
      if (packetSequence == null) {
        statement.setObject(4, null);
      } else {
        statement.setLong(4, packetSequence);
      }
      statement.setString(5, decodeStatus);
      statement.setBoolean(6, synthetic);
      statement.executeUpdate();
    }
  }

  private int countRows(Connection connection, String sessionId) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT count(*) FROM packet_observations WHERE session_id = ?")) {
      statement.setString(1, sessionId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getInt(1);
      }
    }
  }

  private void migrate(String... scripts) throws Exception {
    Path directory = Files.createTempDirectory("packet-observation-migrations");
    try {
      for (String script : scripts) {
        try (var input = getClass().getResourceAsStream("/db/migration/" + script)) {
          if (input == null) {
            throw new IOException("Missing migration resource: " + script);
          }
          Files.copy(input, directory.resolve(script));
        }
      }
      Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .createSchemas(false)
          .baselineOnMigrate(true)
          .baselineVersion("38")
          .locations("filesystem:" + directory)
          .load()
          .migrate();
    } finally {
      try (var paths = Files.walk(directory)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
