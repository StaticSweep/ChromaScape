package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.catalog.CatalogStore;
import com.chromascape.web.aiplayer.schema.AiPlayerCurrentSchema;
import com.chromascape.web.aiplayer.schema.AiPlayerSchemaTarget;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingCatalogPager;
import com.chromascape.web.replay.playback.ReplayPlaybackCatalogRepository;
import com.chromascape.web.replay.playback.ReplayPlaybackCursorCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.*;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses an explicitly disposable PostgreSQL target; database checks cannot silently skip. */
class ProvenancePostgresTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static DriverManagerDataSource source;
  static JdbcTemplate jdbc;
  static SessionFactory factory;
  CatalogStore profiles;
  BehaviorRunContextService contexts;
  CanonicalFrameStore frames;
  RecordingSessionRepository recordings;
  DebugSessionRepository sessions;

  @BeforeAll
  static void database() throws Exception {
    String url = System.getenv("SC665_TEST_JDBC_URL");
    AiPlayerSchemaTarget.validate(url, Set.of(AiPlayerSchemaTarget.PROFILE));
    source = new DriverManagerDataSource(url, "sc665", "sc665-disposable-test");
    new AiPlayerCurrentSchema(source).reset(AiPlayerSchemaTarget.RESET_CONFIRMATION);
    jdbc = new JdbcTemplate(source);
    var settings = new HashMap<String, Object>();
    settings.put("hibernate.connection.datasource", source);
    settings.put("hibernate.default_schema", "ai_player_dev");
    settings.put("hibernate.hbm2ddl.auto", "validate");
    var registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(RecordingSession.class)
            .getMetadataBuilder()
            .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
            .build()
            .buildSessionFactory();
  }

  @AfterAll
  static void close() {
    if (factory != null) factory.close();
  }

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE recording_tick_frames,recording_capture_quality,recording_provenance_audit,debug_sessions,recording_sessions,tick_snapshots,tick_logs RESTART IDENTITY CASCADE");
    profiles = new CatalogStore(jdbc, JSON, new DataSourceTransactionManager(source));
    recordings = mock(RecordingSessionRepository.class);
    sessions = mock(DebugSessionRepository.class);
    when(recordings.findByRecordingId(anyString()))
        .thenAnswer(
            inv -> {
              var rows =
                  jdbc.query(
                      "SELECT * FROM recording_sessions WHERE recording_id=?",
                      (r, n) -> {
                        var row =
                            new RecordingSession(
                                r.getString("recording_id"),
                                r.getString("account_name"),
                                r.getString("script_name"),
                                r.getTimestamp("start_time").toLocalDateTime(),
                                RecordingSession.SessionTrigger.MANUAL,
                                null);
                        row.setId(r.getLong("id"));
                        if (r.getString("task_key") != null)
                          row.captureProvenance(
                              RecordingProvenance.capture(
                                  r.getString("task_key"),
                                  r.getString("task_args") == null
                                      ? null
                                      : parse(r.getString("task_args"))));
                        if (r.getString("run_context") != null)
                          row.bindRunContext(
                              parse(r.getString("run_context")), r.getString("provenance_origin"));
                        return row;
                      },
                      inv.<String>getArgument(0));
              return rows.stream().findFirst();
            });
    when(recordings.save(any()))
        .thenAnswer(
            inv -> {
              RecordingSession row = inv.getArgument(0);
              jdbc.update(
                  "UPDATE recording_sessions SET task_key=?,task_args=?::jsonb,settings_hash=?,run_context=?::jsonb,provenance_origin=? WHERE recording_id=?",
                  row.getTaskKey(),
                  row.getTaskArgs() == null ? null : row.getTaskArgs().toString(),
                  row.getSettingsHash(),
                  row.getRunContext() == null ? null : row.getRunContext().toString(),
                  row.getProvenanceOrigin(),
                  row.getRecordingId());
              return row;
            });
    contexts = new BehaviorRunContextService(profiles, recordings, sessions, jdbc, JSON);
    frames = new CanonicalFrameStore(jdbc, contexts, JSON);
  }

  static JsonNode parse(String s) {
    try {
      return JSON.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void row(String id, String account, String task, String args) {
    jdbc.update(
        "INSERT INTO recording_sessions(recording_id,account_name,script_name,start_time,trigger_type,task_key,task_args,settings_hash) VALUES(?,?,?,?,'MANUAL',?,?::jsonb,?)",
        id,
        account,
        "capture-script",
        java.sql.Timestamp.valueOf("2026-10-02 12:00:00"),
        task,
        args,
        args == null ? null : Micro6CanonicalHash.sha256WithPrefix(parse(args)));
  }

  ObjectNode profile() {
    String hash = "sha256:" + "a".repeat(64);
    var detail = JSON.createObjectNode().put("catalog_hash", hash).put("catalog_version", "2");
    var task =
        detail
            .putObject("task")
            .put("task_key", "FISHING_TRAINING")
            .put("settings_schema_hash", hash);
    task.putObject("args_schema");
    task.putObject("requirements").put("membership", "FREE");
    detail.putObject("catalog_snapshot").put("catalog_hash", hash);
    detail.putObject("source_summary").put("availability", "UNKNOWN");
    detail.putObject("method_facts").put("state", "UNKNOWN");
    return profiles.append(null, "Test", detail, JSON.createObjectNode());
  }

  ObjectNode reference(ObjectNode p) {
    var ref = CanonicalTickFrameTest.context();
    ref.put("behaviorProfileId", p.path("profile_id").asText())
        .put("behaviorProfileVersion", 1)
        .put("semanticContractDigest", BehaviorSemanticContract.digest())
        .put("dataOrigin", "HUMAN");
    return ref;
  }

  void finalized(String recording, String session) {
    var ds =
        new DebugSession(session, "capture-account", "capture-script", LocalDateTime.now(), 0L);
    ds.setRecordingId(recording);
    ds.setEndTick(100L);
    ds.transitionTo(DebugSessionLifecycleState.FINALIZED);
    when(sessions.findByRecordingId(recording)).thenReturn(Optional.of(ds));
  }

  @Test
  void jsonbAndDuplicateTimeCursorFiltersUseRecordingAuthority() throws Exception {
    row("a1", "A", "FISHING_TRAINING", "{}");
    row("a2", "A", "FISHING_TRAINING", "{\"fish\":\"shrimp\"}");
    row("b1", "B", "FISHING_TRAINING", "{}");
    row("video-only", "A", null, null);
    try (var em = factory.createEntityManager()) {
      var pager = new RecordingCatalogPager(em, new ReplayPlaybackCursorCodec(JSON));
      var first = pager.page("A", "FISHING_TRAINING", 1, null);
      assertEquals("a2", first.entries().get(0).getRecordingId());
      assertTrue(first.hasMore());
      var second = pager.page("A", "FISHING_TRAINING", 1, first.nextCursor());
      assertEquals("a1", second.entries().get(0).getRecordingId());
      assertFalse(second.hasMore());
      assertEquals(3, pager.page("A", null, 200, null).entries().size());
      assertEquals(3, pager.page(null, "FISHING_TRAINING", 200, null).entries().size());
      assertEquals(4, pager.page(null, null, 999, null).entries().size());
      assertEquals(1, pager.page(null, null, -1, null).entries().size());
      assertThrows(Exception.class, () -> pager.page(null, null, 1, "bad"));
      assertTrue(first.entries().get(0).getTaskArgs().isObject());
      assertNull(pager.page("A", null, 200, null).entries().get(0).getTaskArgs());
      jdbc.update(
          "INSERT INTO debug_sessions(session_id,account_name,script_name,start_time,start_tick,created_at,recording_id) VALUES('debug-wrong','WRONG','WRONG',now(),0,now(),'a1')");
      jdbc.update(
          "INSERT INTO debug_sessions(session_id,account_name,script_name,start_time,start_tick,created_at,recording_id) VALUES('debug-new','WRONG','WRONG',now(),0,now(),'a1')");
      var repo = new ReplayPlaybackCatalogRepository();
      ReflectionTestUtils.setField(repo, "entityManager", em);
      var rows = repo.findCatalogRows("A", "FISHING_TRAINING", null, null, 201, 0);
      assertEquals(1, rows.size());
      assertEquals("A", rows.get(0)[3]);
      assertEquals("capture-script", rows.get(0)[4]);
      assertEquals("FISHING_TRAINING", rows.get(0)[12]);
      assertEquals("{}", rows.get(0)[13].toString());
      assertTrue(repo.findCatalogRows("WRONG", null, null, 10, 0).isEmpty());
    }
  }

  @Test
  void immutableSnapshotSurvivesNewProfileAndRawSqlTamperingRejected() {
    var p = profile();
    row("capture", "capture-account", "FISHING_TRAINING", "{}");
    var frozen =
        contexts.bind(
            contexts.snapshot(
                reference(p),
                RecordingProvenance.capture("FISHING_TRAINING", JSON.createObjectNode()),
                "capture-account"),
            "capture",
            "00000000-0000-4000-8000-000000000001");
    jdbc.update(
        "UPDATE recording_sessions SET run_context=?::jsonb,provenance_origin='CAPTURED' WHERE recording_id='capture'",
        frozen.toString());
    assertEquals(frozen, contexts.context("capture"));
    var newerDetail =
        JSON.createObjectNode()
            .put("catalog_hash", "sha256:" + "b".repeat(64))
            .put("catalog_version", "3");
    newerDetail.set("task", p.path("task_snapshot"));
    newerDetail.set("catalog_snapshot", p.path("catalog_snapshot").deepCopy());
    ((ObjectNode) newerDetail.path("catalog_snapshot"))
        .put("catalog_hash", "sha256:" + "b".repeat(64));
    newerDetail.set("source_summary", p.path("source_summary"));
    newerDetail.set("method_facts", p.path("method_facts"));
    var newer =
        profiles.append(
            p.path("profile_id").asText(),
            "Changed profile",
            newerDetail,
            JSON.createObjectNode().put("fish", "LATER"));
    assertEquals(2, newer.path("profile_version").asInt());
    assertEquals(frozen, contexts.context("capture"));

    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE recording_sessions SET account_name='later',task_args='{\"fish\":\"later\"}' WHERE recording_id='capture'"));
    assertThrows(
        ProvenanceException.class,
        () ->
            contexts.snapshot(
                reference(p),
                RecordingProvenance.capture(
                    "FISHING_TRAINING", JSON.createObjectNode().put("fish", "different")),
                "capture-account"));
    finalized("capture", frozen.path("debugSessionId").asText());
    assertEquals("CAPTURE_QUALITY_UNKNOWN", contexts.eligibility("capture").get("code"));
  }

  @Test
  void durableDuplicateReceiptSequenceConflictAndQualityFinalization() {
    var p = profile();
    row("capture", "capture-account", "FISHING_TRAINING", "{}");
    var context =
        contexts.bind(
            contexts.snapshot(
                reference(p),
                RecordingProvenance.capture("FISHING_TRAINING", JSON.createObjectNode()),
                "capture-account"),
            "capture",
            "00000000-0000-4000-8000-000000000001");
    jdbc.update(
        "UPDATE recording_sessions SET run_context=?::jsonb WHERE recording_id='capture'",
        context.toString());
    finalized("capture", context.path("debugSessionId").asText());
    var f = CanonicalTickFrameTest.frame();
    f.set("runContext", context);
    CanonicalTickFrameTest.hash(f);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    assertTrue(tx.execute(s -> frames.accept("capture", f)).newFrame());
    assertFalse(tx.execute(s -> frames.accept("capture", f)).newFrame());
    var changed = f.deepCopy().put("tick", 101);
    CanonicalTickFrameTest.hash(changed);
    assertEquals(
        "SEQUENCE_CONFLICT",
        assertThrows(
                ProvenanceException.class, () -> tx.execute(s -> frames.accept("capture", changed)))
            .getCode());
    var gap = f.deepCopy().put("sequence", 3);
    CanonicalTickFrameTest.hash(gap);
    assertEquals(
        "SEQUENCE_GAP",
        assertThrows(
                ProvenanceException.class, () -> tx.execute(s -> frames.accept("capture", gap)))
            .getCode());
    tx.executeWithoutResult(
        s -> frames.close(context.path("debugSessionId").asText(), "EXPLICIT_STOP"));
    assertEquals(true, contexts.eligibility("capture").get("eligible"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("DELETE FROM recording_tick_frames"));
  }

  @Test
  void sharedFrameReplayTrainingAndReadOnlyScoringKeepOneDigestAndUnknowns() {
    var p = profile();
    row("capture", "capture-account", "FISHING_TRAINING", "{}");
    var context =
        contexts.bind(
            contexts.snapshot(
                reference(p),
                RecordingProvenance.capture("FISHING_TRAINING", JSON.createObjectNode()),
                "capture-account"),
            "capture",
            CanonicalTickFrameTest.context().path("debugSessionId").asText());
    jdbc.update(
        "UPDATE recording_sessions SET run_context=?::jsonb WHERE recording_id='capture'",
        context.toString());
    finalized("capture", context.path("debugSessionId").asText());
    var f = CanonicalTickFrameTest.frame();
    f.set("runContext", context);
    CanonicalTickFrameTest.hash(f);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.execute(s -> frames.accept("capture", f));
    tx.executeWithoutResult(
        s -> frames.close(context.path("debugSessionId").asText(), "EXPLICIT_STOP"));
    var consumers = new CanonicalFrameConsumers(frames, contexts, JSON);
    var replay = consumers.page("capture", java.math.BigInteger.ZERO, 1, false);
    var training = consumers.page("capture", java.math.BigInteger.ZERO, 1, true);
    assertEquals(replay, training);
    var request = JSON.createObjectNode();
    request.set("contract", BehaviorSemanticContract.definition());
    var scores = request.putArray("scores");
    for (var id : BehaviorSemanticContract.actionIds()) scores.add(id.equals("WAIT") ? 0 : 10);
    var scored = consumers.score("capture", java.math.BigInteger.ONE, request);
    assertEquals("WAIT", scored.path("actionId").asText());
    assertFalse(scored.path("dispatched").asBoolean());
    assertEquals("UNKNOWN", scored.path("outcome").asText());
    assertEquals(replay.path("entries").get(0).path("observation"), scored.path("observation"));
    assertTrue(
        scored.path("observation").path("fishingState").path("inventory.totalQuantity").isNull());
    assertEquals(f.path("contentHash"), scored.path("frameDigest"));
  }

  @Test
  void disconnectRetainedFramesAreRecoverableButQualityRemainsIncomplete() {
    var p = profile();
    row("capture", "capture-account", "FISHING_TRAINING", "{}");
    var context =
        contexts.bind(
            contexts.snapshot(
                reference(p),
                RecordingProvenance.capture("FISHING_TRAINING", JSON.createObjectNode()),
                "capture-account"),
            "capture",
            CanonicalTickFrameTest.context().path("debugSessionId").asText());
    jdbc.update(
        "UPDATE recording_sessions SET run_context=?::jsonb WHERE recording_id='capture'",
        context.toString());
    finalized("capture", context.path("debugSessionId").asText());
    var f = CanonicalTickFrameTest.frame();
    f.set("runContext", context);
    CanonicalTickFrameTest.hash(f);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.execute(s -> frames.accept("capture", f));
    tx.executeWithoutResult(
        s -> frames.close(context.path("debugSessionId").asText(), "DISCONNECT"));
    var second = f.deepCopy().put("sequence", 2);
    ((ObjectNode) second.path("semanticEvents").get(0))
        .put("eventSequence", 2)
        .put("eventId", UUID.randomUUID().toString());
    CanonicalTickFrameTest.hash(second);
    assertTrue(tx.execute(s -> frames.accept("capture", second)).newFrame());
    assertEquals("2", tx.execute(s -> frames.accept("capture", f)).acceptedSequence());
    assertEquals("CAPTURE_INCOMPLETE", contexts.eligibility("capture").get("code"));
    tx.executeWithoutResult(
        s -> frames.reject(context.path("debugSessionId").asText(), "FRAME_HASH_MISMATCH"));
    assertEquals(
        1,
        parse(
                jdbc.queryForObject(
                    "SELECT quality::text FROM recording_capture_quality", String.class))
            .path("dropped")
            .asInt());
  }

  @Test
  void freshSchemaConvergenceIsIdempotent() {
    new RecordingProvenanceConvergence(source).run(null);
    new RecordingProvenanceConvergence(source).run(null);
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='ai_player_dev' AND indexname LIKE 'idx_recording_catalog_%'",
            Integer.class));
  }

  @Test
  void providerAttributionCannotBeRelabeledAsHumanEvidence() {
    var p = profile();
    var ref = reference(p).put("dataOrigin", "PROVIDER");
    var provenance = RecordingProvenance.capture("FISHING_TRAINING", JSON.createObjectNode());
    assertThrows(
        ProvenanceException.class, () -> contexts.snapshot(ref, provenance, "capture-account"));
    var attribution = ref.putObject("providerAttribution");
    for (String field :
        new String[] {"provider", "model", "approvalScope", "eligibilityContextDigest"})
      attribution.put(field, field);
    var context =
        contexts.bind(
            contexts.snapshot(ref, provenance, "capture-account"),
            "capture",
            CanonicalTickFrameTest.context().path("debugSessionId").asText());
    assertEquals("PROVIDER", context.path("dataOrigin").asText());
    assertEquals(attribution, context.path("providerAttribution"));
    var frame = CanonicalTickFrameTest.frame();
    frame.set("runContext", context);
    ((ObjectNode) frame.path("semanticEvents").get(0)).put("humanObserved", true);
    CanonicalTickFrameTest.hash(frame);
    assertEquals(
        "HUMAN_ACTION_RELABELLED",
        assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.validate(frame, context))
            .getCode());
  }

  @Test
  void auditedLegacyAssignmentNeverPretendsToBeCaptured() {
    var p = profile();
    row("legacy", "capture-account", null, null);
    finalized("legacy", "00000000-0000-4000-8000-000000000001");
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var context =
        tx.execute(
            s -> contexts.assign("legacy", reference(p), "operator", "Reviewed legacy metadata"));
    assertEquals("INFERRED", contexts.recording("legacy").getProvenanceOrigin());
    assertEquals(context, contexts.context("legacy"));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM recording_provenance_audit", Integer.class));
    assertEquals("CAPTURE_QUALITY_UNKNOWN", contexts.eligibility("legacy").get("code"));
    assertThrows(
        ProvenanceException.class,
        () -> tx.execute(s -> contexts.assign("legacy", reference(p), "operator", "Second")));
  }

  @Test
  void migrationIndexesAreRealAndRepeatable() {
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='ai_player_dev' AND indexname LIKE 'idx_recording_catalog_%'",
            Integer.class));
    assertEquals(
        "jsonb",
        jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns WHERE table_schema='ai_player_dev' AND table_name='recording_sessions' AND column_name='task_args'",
            String.class));
  }
}
