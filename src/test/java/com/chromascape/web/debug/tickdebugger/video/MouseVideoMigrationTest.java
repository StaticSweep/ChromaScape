package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-PostgreSQL proof for the additive SC-2150 migration. V42 is applied against a baseline of
 * the prior schema, so the test isolates this migration while still executing real PostgreSQL
 * constraints (not an embedded stand-in).
 */
@Testcontainers(disabledWithoutDocker = true)
class MouseVideoMigrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("41")
        .target("46")
        .load()
        .migrate();
  }

  @Test
  void migrationCreatesAllVideoTables() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      for (String table :
          new String[] {
            "mouse_video_sources",
            "mouse_video_jobs",
            "mouse_video_job_events",
            "mouse_video_analyses",
            "mouse_video_artifact_refs",
            "mouse_video_review_revisions",
            "mouse_video_owner_budgets",
            "mouse_video_intake_reservations",
            "mouse_video_exports",
            "mouse_video_frame_previews"
          }) {
        try (ResultSet rs =
            statement.executeQuery("SELECT to_regclass('public." + table + "') IS NOT NULL")) {
          rs.next();
          assertThat(rs.getBoolean(1)).as("table %s exists", table).isTrue();
        }
      }
    }
  }

  @Test
  void requestIdempotencyIsEnforcedPerOwner() throws SQLException {
    try (Connection connection = connect()) {
      UUID sourceId = insertSource(connection, "owner-1");
      insertJob(connection, sourceId, "owner-1", "req-1");
      assertThatThrownBy(() -> insertJob(connection, sourceId, "owner-1", "req-1"))
          .isInstanceOf(SQLException.class);
      insertJob(connection, sourceId, "owner-2", "req-1");
    }
  }

  @Test
  void exportRoleAccumulatesAndReservationsAreUniquePerOwnerRequest() throws SQLException {
    try (Connection connection = connect()) {
      UUID sourceId = insertSource(connection, "owner-export");
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "INSERT INTO mouse_video_exports (owner, source_id, request_id, request_digest,"
                + " contract, review_revision, manifest_checksum, size_bytes) VALUES"
                + " ('owner-export', '"
                + sourceId
                + "', 'req-a', 'digest-a', 'mouse-video-evidence/v1', 0, 'sha256:a', 10),"
                + " ('owner-export', '"
                + sourceId
                + "', 'req-b', 'digest-b', 'mouse-video-evidence/v1', 1, 'sha256:b', 11)");
        statement.executeUpdate(
            "INSERT INTO mouse_video_artifact_refs (owner, source_id, role, checksum, size_bytes,"
                + " media_type, state) VALUES ('owner-export', '"
                + sourceId
                + "', 'EXPORT', 'sha256:a', 10, 'application/json', 'FINALIZED')");
        statement.executeUpdate(
            "INSERT INTO mouse_video_artifact_refs (owner, source_id, role, checksum, size_bytes,"
                + " media_type, state) VALUES ('owner-export', '"
                + sourceId
                + "', 'EXPORT', 'sha256:b', 11, 'application/json', 'FINALIZED')");
        statement.executeUpdate(
            "INSERT INTO mouse_video_intake_reservations (owner, request_id,"
                + " declared_bytes, expires_at) VALUES ('owner-export', 'req-a', 1, now())");
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "INSERT INTO mouse_video_intake_reservations (owner, request_id,"
                            + " declared_bytes, expires_at) VALUES ('owner-export', 'req-a', 1,"
                            + " now())"))
            .isInstanceOf(SQLException.class);
      }
    }
  }

  @Test
  void reviewRequestIdentityColumnsAreAdditiveAndHistoricalRowsKeepNullDigests()
      throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      UUID sourceId = insertSource(connection, "owner-digest");
      statement.executeUpdate(
          "INSERT INTO mouse_video_review_revisions (source_id, actor, reason, corrections_json)"
              + " VALUES ('"
              + sourceId
              + "', 'owner-digest', 'historical', '[]'::jsonb)");
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT contract_version, request_digest FROM mouse_video_review_revisions"
                  + " WHERE source_id = '"
                  + sourceId
                  + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("corrections/v0");
        assertThat(rs.getString(2)).isNull();
      }
      statement.executeUpdate(
          "INSERT INTO mouse_video_review_revisions (source_id, actor, reason, corrections_json,"
              + " contract_version, request_digest, request_id) VALUES ('"
              + sourceId
              + "', 'owner-digest', 'current', '[]'::jsonb, 'corrections/v1', 'sha256:abc',"
              + " 'req-digest')");
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT contract_version, request_digest FROM mouse_video_review_revisions"
                  + " WHERE request_id = 'req-digest'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("corrections/v1");
        assertThat(rs.getString(2)).isEqualTo("sha256:abc");
      }
    }
  }

  @Test
  void capabilityFlagsCannotClaimMoreThanEvidence() throws SQLException {
    try (Connection connection = connect()) {
      UUID sourceId = insertSource(connection, "owner-flag");
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "INSERT INTO mouse_video_analyses (source_id, owner, original_digest,"
                            + " algorithm_id, config_digest, toolchain_digest, native_telemetry)"
                            + " VALUES ('"
                            + sourceId
                            + "', 'owner-flag', 'sha256:x', 'probe/v1', 'c', 't', true)"))
            .isInstanceOf(SQLException.class);
      }
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static UUID insertSource(Connection connection, String owner) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO mouse_video_sources (id, owner, source_kind) VALUES ('"
              + id
              + "', '"
              + owner
              + "', 'UPLOAD')");
    }
    return id;
  }

  private static void insertJob(
      Connection connection, UUID sourceId, String owner, String requestId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO mouse_video_jobs (id, source_id, owner, kind, request_id, request_digest)"
              + " VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + sourceId
              + "', '"
              + owner
              + "', 'IMPORT', '"
              + requestId
              + "', 'digest')");
    }
  }
}
