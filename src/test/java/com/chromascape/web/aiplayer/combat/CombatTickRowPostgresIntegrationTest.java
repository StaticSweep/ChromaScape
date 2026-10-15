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

/** Real PostgreSQL coverage for V68 and Hibernate JSON save/reload, in a disposable schema. */
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
                  + "DROP COLUMN hazards, DROP COLUMN phase, DROP COLUMN capture_quality");
          statement.execute(
              "INSERT INTO tick_snapshots (session_id, tick, timestamp, player_state) "
                  + "VALUES ('legacy', 1, CURRENT_TIMESTAMP, '{}'::jsonb)");
          String migration;
          try (var in =
              getClass().getResourceAsStream("/db/migration/V68__sc1976_combat_row_sections.sql")) {
            assertThat(in).isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          statement.execute(migration);
          statement.execute(migration); // Idempotent when the table already has the new columns.
          statement.execute("ALTER TABLE debug_sessions DROP COLUMN capture_identity");
          statement.execute(
              "INSERT INTO debug_sessions (session_id,start_time,start_tick,created_at) "
                  + "VALUES ('legacy-identity',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP)");
          try (var in =
              getClass().getResourceAsStream("/db/migration/V70__sc1976_recording_identity.sql")) {
            String identityMigration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(identityMigration);
            statement.execute(identityMigration);
          }
          try (var legacy =
              statement.executeQuery(
                  "SELECT capture_identity FROM debug_sessions WHERE session_id='legacy-identity'")) {
            assertThat(legacy.next()).isTrue();
            assertThat(legacy.getString(1)).isNull();
          }
          assertThatThrownBy(
                  () ->
                      statement.execute(
                          "UPDATE debug_sessions SET capture_identity='[]'::jsonb WHERE session_id='legacy-identity'"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("ck_debug_sessions_capture_identity");
          assertThatThrownBy(
                  () ->
                      statement.execute(
                          "UPDATE debug_sessions SET capture_identity=jsonb_build_object('raw',repeat('x',16384)) WHERE session_id='legacy-identity'"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("ck_debug_sessions_capture_identity");
          try (var in =
              getClass().getResourceAsStream("/db/migration/V69__sc751_capture_quality.sql")) {
            String qualityMigration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(qualityMigration);
            statement.execute(qualityMigration);
          }
          assertThatThrownBy(
                  () ->
                      statement.execute(
                          "UPDATE tick_snapshots SET capture_quality=jsonb_build_object('raw',repeat('x',262144)) WHERE session_id='legacy'"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("ck_tick_snapshots_capture_quality_size");
          try (var result =
              statement.executeQuery(
                  "SELECT inputs, tiles, hazards, phase, capture_quality FROM tick_snapshots "
                      + "WHERE session_id='legacy'")) {
            assertThat(result.next()).isTrue();
            for (int column = 1; column <= 5; column++) {
              assertThat(result.getString(column)).isNull();
            }
          }
          assertThatThrownBy(
                  () ->
                      statement.execute(
                          "UPDATE tick_snapshots SET inputs="
                              + "jsonb_build_object('raw', repeat('x', 262144)) "
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
          var identity = mapper.createObjectNode().put("schema", "debugger-recording-identity/v1");
          var recorded =
              new com.chromascape.web.debug.tickdebugger.entity.DebugSession(
                  "identity-roundtrip", null, "combat", java.time.LocalDateTime.now(), 1L);
          recorded.bindCaptureIdentity(identity);
          try (var session = factory.openSession()) {
            var tx = session.beginTransaction();
            session.persist(recorded);
            tx.commit();
          }
          try (var session = factory.openSession()) {
            var reloaded =
                session.find(
                    com.chromascape.web.debug.tickdebugger.entity.DebugSession.class,
                    recorded.getId());
            assertThat(reloaded.getCaptureIdentity()).isEqualTo(identity);
            assertThatThrownBy(
                    () ->
                        reloaded.bindCaptureIdentity(
                            mapper.createObjectNode().put("schema", "other")))
                .hasMessageContaining("IMMUTABLE_CAPTURE_IDENTITY");
            var copy = reloaded.getCaptureIdentity();
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("schema", "mutated");
            assertThat(reloaded.getCaptureIdentity()).isEqualTo(identity);
          }
          verifyClosureLock(factory);
          TickSnapshot snapshot =
              new TickSnapshot(
                  "combat",
                  110L,
                  java.time.LocalDateTime.of(2026, 10, 5, 14, 0),
                  fixture.get("playerState"));
          try (var in =
              getClass()
                  .getResourceAsStream("/replay/contracts/capture-quality-simulated-v1.json")) {
            snapshot.setCaptureQuality(mapper.readTree(in));
          }
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
            assertThat(reloaded.getCaptureQuality()).isEqualTo(snapshot.getCaptureQuality());
            Map<String, Object> row =
                CombatTickRowService.build(
                    reloaded, null, null, new CombatTickRowService.VideoSync(100L, 30));
            assertThat(row.get("deferred")).isEqualTo(java.util.List.of());
            // Persistence alone must not invent a semantic training label.
            assertThatThrownBy(() -> CombatTickRowService.requireLabelSafe(row))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic action");
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
          .addAnnotatedClass(com.chromascape.web.debug.tickdebugger.entity.DebugSession.class)
          .addAnnotatedClass(com.chromascape.web.debug.tickdebugger.entity.TickLog.class)
          .addAnnotatedClass(com.chromascape.web.debug.tickdebugger.entity.TimelineMarker.class)
          .buildMetadata()
          .buildSessionFactory();
    } catch (RuntimeException error) {
      StandardServiceRegistryBuilder.destroy(registry);
      throw error;
    }
  }

  private static void verifyClosureLock(SessionFactory factory) throws Exception {
    String id = "closure-lock";
    try (var seed = factory.openSession()) {
      var tx = seed.beginTransaction();
      var row =
          new com.chromascape.web.debug.tickdebugger.entity.DebugSession(
              id, "test", "combat", java.time.LocalDateTime.now(), 8L);
      row.setEndTick(322L);
      seed.persist(row);
      tx.commit();
    }
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (var closing = factory.openSession()) {
      var closeTx = closing.beginTransaction();
      var repo =
          new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(closing)
              .getRepository(
                  com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository.class);
      var row = repo.findBySessionIdForUpdate(id).orElseThrow();
      row.setEndTime(java.time.LocalDateTime.now());
      row.setLifecycleState(
          com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState.FINALIZED);
      closing.flush();
      var attempting = new java.util.concurrent.CountDownLatch(1);
      var competing =
          worker.submit(
              () -> {
                try (var ingesting = factory.openSession()) {
                  var tx = ingesting.beginTransaction();
                  var ingestionRepo =
                      new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
                              ingesting)
                          .getRepository(
                              com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository
                                  .class);
                  attempting.countDown();
                  var afterClosure = ingestionRepo.findBySessionIdForUpdate(id).orElseThrow();
                  boolean accepts = afterClosure.acceptsTickEvidence();
                  long bound = afterClosure.getEndTick();
                  tx.commit();
                  return java.util.List.of(accepts, bound);
                }
              });
      assertThat(attempting.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      org.junit.jupiter.api.Assertions.assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> competing.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
      closeTx.commit();
      assertThat(competing.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .containsExactly(false, 322L);
    } finally {
      worker.shutdownNow();
    }
  }
}
