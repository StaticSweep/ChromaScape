package com.chromascape.web.aiplayer.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real SQL rejection/restart/rollback tests; no assumptions or silent PostgreSQL skips. */
class AiPlayerCurrentSchemaTest {
  private static PostgreSQLContainer<?> postgres;
  private static DriverManagerDataSource source;
  private static AiPlayerCurrentSchema schema;
  private static String url;
  private static final String HASH = "sha256:" + "a".repeat(64);

  @BeforeAll
  static void database() throws Exception {
    url = System.getenv("SC665_TEST_JDBC_URL");
    if (url == null) {
      postgres =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withDatabaseName(AiPlayerSchemaTarget.DATABASE)
              .withUsername("sc665")
              .withPassword("sc665-disposable-test");
      postgres.start();
      url = postgres.getJdbcUrl().replace("localhost", "127.0.0.1");
    }
    AiPlayerSchemaTarget.validate(url, Set.of(AiPlayerSchemaTarget.PROFILE));
    source = new DriverManagerDataSource(url, "sc665", "sc665-disposable-test");
    schema = new AiPlayerCurrentSchema(source);
    sql("CREATE TABLE IF NOT EXISTS public.sc665_outside_sentinel(value TEXT PRIMARY KEY)");
    sql("INSERT INTO public.sc665_outside_sentinel VALUES ('preserve-me') ON CONFLICT DO NOTHING");
  }

  @BeforeEach
  void reset() throws Exception {
    schema.reset(AiPlayerSchemaTarget.RESET_CONFIRMATION);
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
  }

  @Test
  void freshSchemaBootstrapsAndValidatesEveryCurrentJpaEntity() throws Exception {
    sql("DROP SCHEMA ai_player_dev CASCADE");
    schema.bootstrap();
    schema.verify();
    Map<String, Object> settings = new HashMap<>();
    settings.put("hibernate.connection.datasource", source);
    settings.put("hibernate.default_schema", "ai_player_dev");
    settings.put("hibernate.hbm2ddl.auto", "validate");
    try (var registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
        var sessionFactory = CurrentEntityMappings.metadata(registry).buildSessionFactory()) {
      assertTrue(sessionFactory.getMetamodel().getEntities().size() >= 76);
    }
  }

  @Test
  void twoResetsReproduceIdentityAndFixtureWithoutTouchingPublicData() throws Exception {
    String before = fingerprint();
    String fixture = scalar("SELECT row_to_json(p)::text FROM ai_player_behavior_profiles p");
    schema.reset(AiPlayerSchemaTarget.RESET_CONFIRMATION);
    assertEquals(before, fingerprint());
    assertEquals(fixture, scalar("SELECT row_to_json(p)::text FROM ai_player_behavior_profiles p"));
    assertEquals("preserve-me", scalar("SELECT value FROM public.sc665_outside_sentinel"));
    assertEquals(
        Integer.toString(AiPlayerCurrentSchema.MIGRATION_COUNT),
        scalar("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL"));
  }

  @Test
  void publishedProfileAndDatasetAreImmutable() throws Exception {
    rejected("UPDATE ai_player_behavior_profiles SET task_key='OTHER'", "23514");
    rejected("DELETE FROM ai_player_behavior_profiles", "23514");
    sql(
        "INSERT INTO ai_player_datasets VALUES ('dataset-1','"
            + HASH
            + "','{}','2026-10-02T00:00:00Z')");
    rejected("UPDATE ai_player_datasets SET manifest_json='{\"changed\":true}'", "23514");
    rejected("DELETE FROM ai_player_datasets", "23514");
    rejected(
        "INSERT INTO ai_player_behavior_profiles SELECT * FROM ai_player_behavior_profiles",
        "23505");
  }

  @Test
  void publishedGraphsModelsAndEvidenceAreAppendOnly() throws Exception {
    sql(
        "INSERT INTO behavior_graph_definitions(graph_id,graph_hash,identity_json,definition_json,state,created_at) "
            + "VALUES ('graph-1','"
            + HASH
            + "','{}','{}','PUBLISHED','2026-10-02T00:00:00Z')");
    rejected("UPDATE behavior_graph_definitions SET definition_json='[]'", "23514");
    rejected("DELETE FROM behavior_graph_definitions", "23514");
    sql(
        "INSERT INTO routine_models(model_id,graph_id,model_hash,identity_json,definition_json,created_at) "
            + "VALUES ('model-1','graph-1','"
            + HASH
            + "','{}','{}','2026-10-02T00:00:00Z')");
    rejected("UPDATE routine_models SET definition_json='[]'", "23514");
    rejected("DELETE FROM routine_models", "23514");
    rejected(
        "INSERT INTO routine_models(model_id,graph_id,model_hash,identity_json,definition_json,created_at) "
            + "VALUES ('model-2','missing','"
            + HASH
            + "','{}','{}','2026-10-02T00:00:00Z')",
        "23503");
  }

  @Test
  void modelCannotAttachToGraphFromAnotherRun() throws Exception {
    sql(
        "INSERT INTO behavior_graph_definitions(graph_id,graph_hash,identity_json,definition_json,state,created_at) "
            + "VALUES ('graph-1','"
            + HASH
            + "','"
            + identity("run-1")
            + "','{}','PUBLISHED','2026-10-02')");
    rejected(
        "INSERT INTO routine_models(model_id,graph_id,model_hash,identity_json,definition_json,created_at) "
            + "VALUES ('model-1','graph-1','"
            + HASH
            + "','"
            + identity("run-2")
            + "','{}','2026-10-02')",
        "23514");
  }

  @Test
  void captureCannotCompleteWithoutHashesOrFinalizedEvidence() throws Exception {
    capture("session-1", "debug-1", "recording-1");
    rejected(
        "UPDATE routine_capture_records SET completed=true,completion_status='COMPLETED'", "23514");
    rejected(
        "UPDATE routine_capture_records SET completed=true,completion_status='COMPLETED',final_tick=2,"
            + "recording_hash='"
            + HASH
            + "',video_sha256='"
            + HASH
            + "',completion_request_hash='"
            + HASH
            + "'",
        "23514");
    assertEquals("f", scalar("SELECT completed FROM routine_capture_records"));
  }

  @Test
  void completionIsAtomicAndFinalizedCaptureCannotBeChanged() throws Exception {
    physicalRecording("debug-1", "recording-1");
    capture("session-1", "debug-1", "recording-1");
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      execute(connection, "UPDATE recording_sessions SET end_time='2026-10-02T00:01:00'");
      execute(connection, "UPDATE debug_sessions SET end_time='2026-10-02T00:01:00',end_tick=2");
      complete(connection);
      connection.commit();
    }
    assertEquals("t", scalar("SELECT completed FROM routine_capture_records"));
    rejected("UPDATE routine_capture_records SET final_tick=3", "23514");
    rejected("DELETE FROM routine_capture_records", "23514");
    rejected("UPDATE debug_sessions SET end_time=NULL", "23514");
    rejected("UPDATE recording_sessions SET end_time=NULL", "23514");
  }

  @Test
  void interruptedFinalizationRollsBackAllWrites() throws Exception {
    physicalRecording("debug-1", "recording-1");
    capture("session-1", "debug-1", "recording-1");
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      execute(connection, "UPDATE recording_sessions SET end_time='2026-10-02T00:01:00'");
      complete(connection);
      connection.rollback();
    }
    assertEquals("f", scalar("SELECT completed FROM routine_capture_records"));
    assertNull(scalar("SELECT end_time FROM recording_sessions"));
  }

  @Test
  void crossIdentityBindingsFailAtCommitAndValidBindingIsImmutable() throws Exception {
    // Deferred tuple FKs permit same-transaction finalization without permitting mixed identities.
    run("run-1");
    physicalRecording("debug-1", "recording-1");
    physicalRecording("debug-2", "recording-2");
    capture("session-1", "debug-1", "recording-1");
    rejected(binding("run-1", "session-1", "debug-2", "recording-2"), "23503");
    run("run-2");
    rejected(binding("run-2", "session-1", "debug-1", "recording-1"), "23514");
    sql(binding("run-1", "session-1", "debug-1", "recording-1"));
    rejected("UPDATE ai_player_run_bindings SET client_id='other'", "23514");
    rejected(binding("run-1", "session-1", "debug-1", "recording-1"), "23505");
  }

  @Test
  void assistantEventsCannotAttachToMissingRunOrConversation() throws Exception {
    rejected(
        "INSERT INTO routine_assistant_conversations(conversation_id,run_id,state,next_event_id,generation_attempts,created_at) "
            + "VALUES ('conversation','missing','OPEN',0,0,'2026-10-02T00:00:00Z')",
        "23503");
    run("run-1");
    sql(
        "INSERT INTO routine_assistant_conversations(conversation_id,run_id,state,next_event_id,generation_attempts,created_at) "
            + "VALUES ('conversation','run-1','OPEN',0,0,'2026-10-02T00:00:00Z')");
    sql(
        "INSERT INTO routine_assistant_events(conversation_id,event_id,type,data_json,created_at) "
            + "VALUES ('conversation',1,'EVIDENCE','{}','2026-10-02T00:00:00Z')");
    rejected("UPDATE routine_assistant_events SET data_json='[]'", "23514");
    rejected(
        "INSERT INTO routine_assistant_events(conversation_id,event_id,type,created_at) "
            + "VALUES ('missing',1,'EVIDENCE','2026-10-02T00:00:00Z')",
        "23503");
  }

  @Test
  void successfulJobsNeedResultAndTerminalStateIsWriteOnce() throws Exception {
    run("run-1");
    rejected(
        "INSERT INTO ai_player_jobs VALUES ('job','run-1','"
            + HASH
            + "','SUCCEEDED',NULL,'2026-10-02T00:00:00Z','2026-10-02T00:00:00Z')",
        "23514");
    sql(
        "INSERT INTO ai_player_jobs VALUES ('job','run-1','"
            + HASH
            + "','PENDING',NULL,'2026-10-02T00:00:00Z',NULL)");
    sql(
        "UPDATE ai_player_jobs SET state='SUCCEEDED',result_json='{}',completed_at='2026-10-02T00:00:00Z'");
    rejected("UPDATE ai_player_jobs SET state='FAILED'", "23514");
    rejected("DELETE FROM ai_player_jobs", "23514");
  }

  @Test
  void driftOrMissingIdentityRejectsBootstrapWithoutHealing() throws Exception {
    sql("ALTER TABLE ai_player_jobs ADD COLUMN unexpected TEXT");
    assertThrows(IllegalStateException.class, schema::bootstrap);
    assertEquals(
        "1",
        scalar(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='ai_player_dev' "
                + "AND table_name='ai_player_jobs' AND column_name='unexpected'"));
    schema.reset(AiPlayerSchemaTarget.RESET_CONFIRMATION);
    sql("DROP TABLE ai_player_schema_identity");
    assertThrows(IllegalStateException.class, schema::bootstrap);
    assertEquals(
        Integer.toString(AiPlayerCurrentSchema.MIGRATION_COUNT),
        scalar("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL"));
  }

  @Test
  void alteredTriggerOrUnexpectedMigrationHistoryIsRejected() throws Exception {
    sql("ALTER TABLE ai_player_behavior_profiles DISABLE TRIGGER ai_player_profile_immutable");
    assertThrows(IllegalStateException.class, schema::verify);
    schema.reset(AiPlayerSchemaTarget.RESET_CONFIRMATION);
    sql("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");
    assertThrows(IllegalStateException.class, schema::bootstrap);
  }

  @Test
  void wrongIdentityIsRejectedEvenWithValidStructure() throws Exception {
    // Simulate privileged corruption without changing the physical shape.
    sql(
        "SET session_replication_role=replica; DELETE FROM ai_player_schema_identity; SET session_replication_role=origin");
    assertThrows(IllegalStateException.class, schema::verify);
  }

  @Test
  void resetRefusesMissingConfirmationAndExternalDependencies() throws Exception {
    String before = fingerprint();
    assertThrows(IllegalArgumentException.class, () -> schema.reset(null));
    assertEquals(before, fingerprint());
    sql("CREATE VIEW public.sc665_outside_dependency AS SELECT * FROM ai_player_behavior_profiles");
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> schema.reset(AiPlayerSchemaTarget.RESET_CONFIRMATION));
      assertEquals(before, fingerprint());
    } finally {
      sql("DROP VIEW public.sc665_outside_dependency");
    }
  }

  @Test
  void sharedRemoteUnresolvedAndMixedProfileTargetsAreRejected() {
    for (String unsafe :
        new String[] {
          "",
          "jdbc:postgresql://localhost:5433/chromascape",
          "jdbc:postgresql://192.168.1.6:5433/chromascape_aiplayer_dev",
          "jdbc:postgresql://127.0.0.1:5433/chromascape_aiplayer_dev?currentSchema=public",
          "jdbc:postgresql://127.0.0.1:0/chromascape_aiplayer_dev",
          "jdbc:postgresql://127.0.0.1:5433/chromascape_aiplayer_dev?options=-csearch_path=public"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> AiPlayerSchemaTarget.validate(unsafe, Set.of(AiPlayerSchemaTarget.PROFILE)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AiPlayerSchemaTarget.validate(url, Set.of("production", AiPlayerSchemaTarget.PROFILE)));
    assertThrows(
        IllegalArgumentException.class, () -> AiPlayerSchemaTarget.validate(null, Set.of()));
  }

  @Test
  void startupStrategyRejectsDriftBeforeAllowingJpaInitialization() throws Exception {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(AiPlayerSchemaTarget.PROFILE);
    environment.setProperty("spring.datasource.url", url);
    environment.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
    environment.setProperty("spring.jpa.properties.hibernate.default_schema", "ai_player_dev");
    environment.setProperty("spring.flyway.locations", AiPlayerCurrentSchema.LOCATION);
    environment.setProperty("spring.flyway.enabled", "true");
    var configuration = new AiPlayerSchemaConfiguration();
    var strategy = configuration.aiPlayerCurrentSchemaStrategy(source, environment);
    strategy.migrate(AiPlayerCurrentSchema.migrations(source));
    sql("DROP INDEX ai_player_jobs_claim");
    assertThrows(
        IllegalStateException.class,
        () -> strategy.migrate(AiPlayerCurrentSchema.migrations(source)));
    environment.setProperty("spring.jpa.hibernate.ddl-auto", "update");
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration.aiPlayerCurrentSchemaStrategy(source, environment));
  }

  @Test
  void unsafeSpringConfigurationFailsBeforeAnyApplicationBeanIsCreated() {
    var created = new AtomicBoolean();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles(AiPlayerSchemaTarget.PROFILE);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "unsafe-test",
                  Map.of("spring.datasource.url", url, "spring.jpa.hibernate.ddl-auto", "update")));
      context.register(AiPlayerSchemaConfiguration.class);
      context.registerBean(
          "applicationSentinel",
          Object.class,
          () -> {
            created.set(true);
            return new Object();
          });
      assertThrows(Exception.class, context::refresh);
      assertFalse(created.get());
    }
  }

  @Test
  void sourceBytesBindingsAndReadAuditRetainOriginalImmutability() throws Exception {
    sql(
        "INSERT INTO source_content_objects(id,content_hash,size_bytes,content_bytes,created_at) "
            + "VALUES ('00000000-0000-0000-0000-000000000001','"
            + HASH
            + "',1,decode('61','hex'),'2026-10-02')");
    rejected("UPDATE source_content_objects SET content_bytes=decode('62','hex')", "P0001");
    rejected("DELETE FROM source_content_objects", "23514");
    rejected(
        "INSERT INTO source_run_bindings(id,run_id,task_key,manifest_hash,snapshot_id,verification_state,source_provenance,created_at) "
            + "VALUES ('00000000-0000-0000-0000-000000000002','run-1','FISHING_TRAINING','"
            + HASH
            + "','snapshot','VERIFIED','CURRENT','2026-10-02')",
        "23503");
    sql(
        "INSERT INTO source_read_audit_events(id,actor_id,operation,result,created_at) "
            + "VALUES ('00000000-0000-0000-0000-000000000003','fixture','READ','REFUSED','2026-10-02')");
    rejected("UPDATE source_read_audit_events SET result='SUCCESS'", "23514");
    rejected("DELETE FROM source_read_audit_events", "23514");
  }

  @Test
  void candidateCannotClaimRegistrationWithoutFinalEvidence() throws Exception {
    sql(
        "INSERT INTO patch_candidate_runs(base_commit,binding_json,end_line,failure_manifest_hash,patch_hash,patch_text,"
            + "preimage_hash,proposal_hash,proposal_id,source_file_id,source_manifest_hash,source_manifest_json,"
            + "source_relative_path,source_snapshot_id,start_line,state,updated_at) VALUES ('commit','{}',1,'"
            + HASH
            + "','"
            + HASH
            + "','patch','"
            + HASH
            + "','"
            + HASH
            + "','proposal','file','"
            + HASH
            + "','{}','source.java','snapshot',1,'PROPOSED','2026-10-02')");
    rejected("UPDATE patch_candidate_runs SET state='REGISTERED'", "23514");
    sql(
        "UPDATE patch_candidate_runs SET state='REGISTERED',candidate_id='candidate',candidate_hash='"
            + HASH
            + "',approval_hash='"
            + HASH
            + "',approved_at='2026-10-02',manifest_file_hash='"
            + HASH
            + "',jar_sha256='"
            + HASH
            + "',sbom_sha256='"
            + HASH
            + "',certification_document_hash='"
            + HASH
            + "',registry_response_hash='"
            + HASH
            + "',registry_verified_at='2026-10-02',final_identity_json='{}',result_json='{}'");
    rejected("UPDATE patch_candidate_runs SET result_json='[]'", "23514");
    rejected("DELETE FROM patch_candidate_runs", "23514");
  }

  @org.springframework.boot.autoconfigure.domain.EntityScan("com.chromascape")
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class EntityScanConfiguration {}

  @Test
  void springAutoConfigurationValidatesAllEntitiesAndRejectsDriftBeforeJpa() throws Exception {
    var runner =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withConfiguration(
                org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
                        .class,
                    org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class))
            .withUserConfiguration(EntityScanConfiguration.class, AiPlayerSchemaConfiguration.class)
            .withPropertyValues(
                "spring.profiles.active=" + AiPlayerSchemaTarget.PROFILE,
                "spring.datasource.url=" + url,
                "spring.datasource.username=sc665",
                "spring.datasource.password=sc665-disposable-test",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=ai_player_dev",
                "spring.flyway.locations=" + AiPlayerCurrentSchema.LOCATION,
                "spring.flyway.enabled=true");
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertNotNull(context.getBean(jakarta.persistence.EntityManagerFactory.class));
        });
    sql("DROP INDEX ai_player_jobs_claim");
    runner.run(
        context -> {
          assertNotNull(context.getStartupFailure());
          Throwable cause = context.getStartupFailure();
          boolean schemaRejected = false;
          while (cause != null) {
            schemaRejected |=
                cause.getMessage() != null
                    && cause.getMessage().contains("AI_PLAYER_SCHEMA_STARTUP_REJECTED");
            cause = cause.getCause();
          }
          assertTrue(schemaRejected);
        });
  }

  private static String identity(String run) {
    return "{\"run\":{\"run_id\":\""
        + run
        + "\"},\"client\":{\"client_id\":\"fixture-client\"},"
        + "\"task\":{\"task_key\":\"FISHING_TRAINING\",\"profile_id\":\"sc665-fishing-fixture\","
        + "\"catalog_hash\":\""
        + HASH
        + "\",\"settings_hash\":\"sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\"}}";
  }

  private static String binding(String run, String session, String debug, String recording) {
    return "INSERT INTO ai_player_run_bindings VALUES ('"
        + run
        + "','sc665-fishing-fixture',1,'fixture-client',"
        + "'fixture-account','"
        + session
        + "','"
        + debug
        + "','"
        + recording
        + "')";
  }

  private static void run(String id) throws Exception {
    sql(
        "INSERT INTO current_slice_runs(run_id,identity_json,stage,control_allowed,created_at) "
            + "VALUES ('"
            + id
            + "','"
            + identity(id)
            + "','CREATED',false,'2026-10-02T00:00:00Z')");
  }

  private static void capture(String session, String debug, String recording) throws Exception {
    sql(
        "INSERT INTO routine_capture_records(session_id,start_idempotency_key,start_request_hash,identity_json,started_at,"
            + "start_tick,ticks_json,actions_json,tick_sequences_json,action_sequences_json,tick_idempotency_json,"
            + "action_idempotency_json,request_idempotency_json,next_tick_sequence,next_action_sequence,completed,"
            + "completion_status,operation_attempts,debug_session_id,recording_id) VALUES ('"
            + session
            + "','key-"
            + session
            + "','"
            + HASH
            + "','"
            + identity("run-1")
            + "','2026-10-02T00:00:00Z',1,'[]','[]','{}','{}','{}','{}','{}',1,1,false,'RECORDING',0,'"
            + debug
            + "','"
            + recording
            + "')");
  }

  private static void physicalRecording(String debug, String recording) throws Exception {
    sql(
        "INSERT INTO recording_sessions(recording_id,start_time,trigger_type) VALUES ('"
            + recording
            + "','2026-10-02','MANUAL')");
    sql(
        "INSERT INTO debug_sessions(session_id,start_time,start_tick,created_at,recording_id) VALUES ('"
            + debug
            + "','2026-10-02',1,'2026-10-02','"
            + recording
            + "')");
  }

  private static void complete(Connection connection) throws Exception {
    execute(
        connection,
        "UPDATE routine_capture_records SET completed=true,completion_status='COMPLETED',final_tick=2,"
            + "recording_hash='"
            + HASH
            + "',video_sha256='"
            + HASH
            + "',completion_request_hash='"
            + HASH
            + "'");
  }

  private static Connection connection() throws Exception {
    Connection connection = source.getConnection();
    execute(connection, "SET search_path TO ai_player_dev, pg_catalog");
    return connection;
  }

  private static void sql(String query) throws Exception {
    try (Connection connection = connection()) {
      execute(connection, query);
    }
  }

  private static void execute(Connection connection, String query) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(query);
    }
  }

  private static String scalar(String query) throws Exception {
    try (Connection connection = connection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  private static String fingerprint() throws Exception {
    try (Connection connection = connection()) {
      return AiPlayerCurrentSchema.fingerprint(connection);
    }
  }

  private static void rejected(String query, String state) {
    SQLException exception = assertThrows(SQLException.class, () -> sql(query));
    assertEquals(state, exception.getSQLState(), exception.getMessage());
  }
}
