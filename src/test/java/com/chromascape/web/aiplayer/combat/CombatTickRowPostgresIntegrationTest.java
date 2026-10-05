package com.chromascape.web.aiplayer.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL coverage for V66 and Hibernate JSON save/reload, in a disposable schema. */
class CombatTickRowPostgresIntegrationTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void migrationPreservesLegacyRowsAndCombatSectionsSurviveReload() throws Exception {
    String url = System.getenv("SC1976_TEST_JDBC_URL");
    String user = System.getenv("SC1976_TEST_JDBC_USER");
    String password = System.getenv("SC1976_TEST_JDBC_PASSWORD");
    PostgreSQLContainer<?> container = null;
    if (url == null) {
      container =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .waitingFor(HostPortWaitStrategy.postgres());
      container.start();
      url = container.getJdbcUrl();
      user = container.getUsername();
      password = container.getPassword();
    }
    String schema = "sc1976_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin = DriverManager.getConnection(url, user, password)) {
      try (var statement = admin.createStatement()) {
        statement.execute("CREATE SCHEMA " + schema);
      }
      try {
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        // Reproduce the existing Hibernate-owned snapshot table before the additive migration.
        try (SessionFactory ignored = factory(scopedUrl, user, password, "create")) {}
        try (Connection connection = DriverManager.getConnection(scopedUrl, user, password);
            var statement = connection.createStatement()) {
          statement.execute(
              "ALTER TABLE tick_snapshots DROP COLUMN inputs, DROP COLUMN tiles, "
                  + "DROP COLUMN hazards, DROP COLUMN phase");
          statement.execute(
              "INSERT INTO tick_snapshots (session_id, tick, timestamp, player_state) "
                  + "VALUES ('legacy', 1, CURRENT_TIMESTAMP, '{}'::jsonb)");
          String migration;
          try (var in =
              getClass().getResourceAsStream("/db/migration/V66__sc1976_combat_row_sections.sql")) {
            assertThat(in).isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          statement.execute(migration);
          statement.execute(migration); // Idempotent when the table already has the new columns.
          try (var result =
              statement.executeQuery(
                  "SELECT inputs, tiles, hazards, phase FROM tick_snapshots WHERE session_id='legacy'")) {
            assertThat(result.next()).isTrue();
            for (int column = 1; column <= 4; column++) {
              assertThat(result.getString(column)).isNull();
            }
          }
          assertThatThrownBy(
                  () ->
                      statement.execute(
                          "UPDATE tick_snapshots SET inputs=jsonb_build_object('raw', repeat('x', 262144)) "
                              + "WHERE session_id='legacy'"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("ck_tick_snapshots_combat_row_json_sizes");
        }
        JsonNode fixture;
        try (var in =
            getClass()
                .getResourceAsStream("/replay/contracts/combat-tick-row-schema4-fixture-v1.json")) {
          fixture = mapper.readTree(in);
        }
        try (SessionFactory factory = factory(scopedUrl, user, password, "validate")) {
          TickSnapshot snapshot =
              new TickSnapshot(
                  "combat",
                  110L,
                  java.time.LocalDateTime.of(2026, 10, 5, 14, 0),
                  fixture.get("playerState"));
          snapshot.setInputs(fixture.get("inputs"));
          snapshot.setTiles(fixture.get("tiles"));
          snapshot.setHazards(fixture.get("hazards"));
          snapshot.setPhase(fixture.get("phase"));
          snapshot.setNpcs(fixture.get("npcs"));
          try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(snapshot);
            transaction.commit();
          }
          try (var session = factory.openSession()) {
            TickSnapshot reloaded = session.find(TickSnapshot.class, snapshot.getId());
            assertThat(reloaded.getInputs()).isEqualTo(fixture.get("inputs"));
            assertThat(reloaded.getTiles()).isEqualTo(fixture.get("tiles"));
            assertThat(reloaded.getHazards()).isEqualTo(fixture.get("hazards"));
            assertThat(reloaded.getPhase()).isEqualTo(fixture.get("phase"));
            Map<String, Object> row =
                CombatTickRowService.build(
                    reloaded, null, null, new CombatTickRowService.VideoSync(100L, 30));
            assertThat(row.get("deferred")).isEqualTo(java.util.List.of());
            CombatTickRowService.requireLabelSafe(row);
          }
        }
      } finally {
        try (var statement = admin.createStatement()) {
          statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
      }
    } finally {
      if (container != null) {
        container.stop();
      }
    }
  }

  private static SessionFactory factory(String url, String user, String password, String ddl) {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySettings(
                Map.of(
                    "hibernate.connection.url",
                    url,
                    "hibernate.connection.username",
                    user,
                    "hibernate.connection.password",
                    password,
                    "hibernate.hbm2ddl.auto",
                    ddl,
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"))
            .build();
    try {
      return new MetadataSources(registry)
          .addAnnotatedClass(TickSnapshot.class)
          .buildMetadata()
          .buildSessionFactory();
    } catch (RuntimeException error) {
      StandardServiceRegistryBuilder.destroy(registry);
      throw error;
    }
  }
}
