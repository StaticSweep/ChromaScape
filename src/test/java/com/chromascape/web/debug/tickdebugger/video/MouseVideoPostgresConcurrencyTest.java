package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Real-PostgreSQL concurrency coverage for the SC-2150 remediation. Two independent sessions prove
 * that a queued job is claimed once and that an owner/request reservation is unique. This class is
 * skipped only when Docker is unavailable; it must not be reported as a passing concurrency gate in
 * that case.
 */
@Testcontainers(disabledWithoutDocker = true)
class MouseVideoPostgresConcurrencyTest {

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
  void twoSessionsClaimAQueuedJobOnlyOnce() throws SQLException {
    UUID sourceId = insertSource("owner-claim");
    UUID jobId = insertJob(sourceId, "owner-claim", "req-claim");
    try (Connection first = connect();
        Connection second = connect()) {
      // Each conditional UPDATE is its own committed transaction, matching the service's short
      // lock/claim transaction. The second session observes RUNNING and affects zero rows.
      first.setAutoCommit(true);
      second.setAutoCommit(true);

      int firstClaim = conditionalClaim(first, jobId, "worker-1");
      int secondClaim = conditionalClaim(second, jobId, "worker-2");

      assertThat(firstClaim).isEqualTo(1);
      assertThat(secondClaim).isZero();
      assertThat(statusOf(jobId)).isEqualTo("RUNNING");
      assertThat(leaseOwnerOf(jobId)).isEqualTo("worker-1");
    }
  }

  @Test
  void ownerRequestReservationIsUnique() throws SQLException {
    String owner = "owner-reservation";
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO mouse_video_intake_reservations (owner, request_id, declared_bytes,"
              + " expires_at) VALUES ('"
              + owner
              + "', 'req', 10, now())");
      int duplicates = 0;
      try {
        statement.executeUpdate(
            "INSERT INTO mouse_video_intake_reservations (owner, request_id, declared_bytes,"
                + " expires_at) VALUES ('"
                + owner
                + "', 'req', 10, now())");
      } catch (SQLException expected) {
        duplicates = 1;
      }
      assertThat(duplicates).isEqualTo(1);
    }
  }

  private static int conditionalClaim(Connection connection, UUID jobId, String worker)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      return statement.executeUpdate(
          "UPDATE mouse_video_jobs SET status = 'RUNNING', generation = generation + 1,"
              + " lease_owner = '"
              + worker
              + "', lease_expires_at = now() + interval '30 seconds', updated_at = now()"
              + " WHERE id = '"
              + jobId
              + "' AND status = 'QUEUED'");
    }
  }

  private static String statusOf(UUID jobId) throws SQLException {
    return single(jobId, "status");
  }

  private static String leaseOwnerOf(UUID jobId) throws SQLException {
    return single(jobId, "lease_owner");
  }

  private static String single(UUID jobId, String column) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM mouse_video_jobs WHERE id = '" + jobId + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static UUID insertSource(String owner) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO mouse_video_sources (id, owner, source_kind) VALUES ('"
              + id
              + "', '"
              + owner
              + "', 'UPLOAD')");
    }
    return id;
  }

  private static UUID insertJob(UUID sourceId, String owner, String requestId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO mouse_video_jobs (id, source_id, owner, kind, request_id, request_digest)"
              + " VALUES ('"
              + id
              + "', '"
              + sourceId
              + "', '"
              + owner
              + "', 'IMPORT', '"
              + requestId
              + "', 'digest')");
    }
    return id;
  }
}
