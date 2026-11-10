package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SC-1498 V89 upgrade proof: an already-populated V88 database upgrades in place, keeps its rows,
 * and the new bootstrap tables and guards are usable afterwards.
 *
 * <p>Runs against its own disposable PostgreSQL container; it never touches a developer database.
 */
@Testcontainers
class Sc1498UpgradeFromV88IntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("sc1498_upgrade")
          .withUsername("test")
          .withPassword("test");

  @Test
  void aPopulatedV88DatabaseUpgradesToTheCurrentHeadAndKeepsItsRows() throws Exception {
    migrateTo("88");
    assertThat(currentVersion()).isEqualTo("88");

    // Data present before the upgrade: an existing orchestration row and its terminal result.
    execute(
        "INSERT INTO ab_orchestration (orchestration_id, request_id, idempotency_key,"
            + " request_fingerprint, envelope_digest, source_type, mode, state, failure_code,"
            + " request_json, view_json, retry_allowed, view_version, created_at, updated_at,"
            + " entity_version) VALUES ('orch-upgrade-1','req-upgrade-1','idem-upgrade-1',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'PROVIDER:CLAUDE','BOOTSTRAP_RECORDING','ACTIVE','NONE','{}','{}',FALSE,0,"
            + " now(),now(),0)");

    migrateTo(null);

    assertThat(currentVersion()).isEqualTo("90");
    assertThat(
            queryInt(
                "SELECT count(*) FROM ab_orchestration WHERE orchestration_id='orch-upgrade-1'"))
        .as("pre-existing rows survive the upgrade")
        .isEqualTo(1);
    assertThat(
            queryInt(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN"
                    + " ('gameplay_bootstrap_approval','gameplay_bootstrap_recording',"
                    + " 'gameplay_bootstrap_v2_draft')"))
        .isEqualTo(3);

    // The new intent guard is live immediately after the upgrade.
    execute(
        "INSERT INTO gameplay_bootstrap_recording (orchestration_id, recording_id,"
            + " provider_session_id, provider_turn_id, decision_digest, envelope_digest,"
            + " provider, model_version, manifest_state, created_at) VALUES ('orch-upgrade-1',"
            + " 'rec-upgrade-1','session-u','turn-u',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'CLAUDE','model-v1','STARTED',now())");
    execute(
        "UPDATE gameplay_bootstrap_recording SET finalization_requested_at=now()"
            + " WHERE orchestration_id='orch-upgrade-1'");
    assertThat(
            queryInt(
                "SELECT count(*) FROM gameplay_bootstrap_recording"
                    + " WHERE orchestration_id='orch-upgrade-1'"
                    + " AND finalization_requested_at IS NOT NULL"))
        .isEqualTo(1);
  }

  private void migrateTo(String target) {
    ClassicConfiguration configuration = new ClassicConfiguration();
    configuration.setDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    configuration.setLocationsAsStrings("classpath:db/migration");
    configuration.setBaselineOnMigrate(true);
    configuration.setBaselineVersion(MigrationVersion.fromVersion("0"));
    configuration.setOutOfOrder(true);
    if (target != null) {
      configuration.setTarget(MigrationVersion.fromVersion(target));
    }
    assertThat(new Flyway(configuration).migrate().success).isTrue();
  }

  private String currentVersion() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"
                    + " DESC LIMIT 1")) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getString(1);
    }
  }

  private int queryInt(String sql) throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getInt(1);
    }
  }

  private void execute(String sql) throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
