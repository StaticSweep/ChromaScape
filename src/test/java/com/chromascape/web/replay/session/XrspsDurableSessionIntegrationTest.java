package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.error.ReplayExceptionHandler;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit native integration gate: disposable PostgreSQL + the committed real-service worker. */
@EnabledIfEnvironmentVariable(named = "SC1991_WORKER_ENTRYPOINT", matches = ".+")
class XrspsDurableSessionIntegrationTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final CanonicalProjectionService CANONICAL = new CanonicalProjectionService();
  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager manager;
  private static JsonNode capabilities;
  private static String testSchema;
  private static ReplayProperties properties;
  private XrspsSessionRepository repository;
  private ScenarioManifestRepository manifests;
  private XrspsDurableSessionService service;
  private UUID scenarioId;

  @BeforeAll
  static void connect() throws Exception {
    String url = System.getenv("SC1991_TEST_JDBC_URL");
    assertNotNull(url, "SC1991_TEST_JDBC_URL must point to a disposable test database");
    testSchema = "sc1991_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(new DriverManagerDataSource(url, "test", "test"))
        .execute("CREATE SCHEMA " + testSchema);
    var ds =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + testSchema, "test", "test");
    jdbc = new JdbcTemplate(ds);
    manager = new DataSourceTransactionManager(ds);
    try (var input =
        XrspsDurableSessionIntegrationTest.class.getResourceAsStream(
            "/db/migration/V66__sc1991_durable_sessions.sql")) {
      assertNotNull(input);
      jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    try (var input =
        XrspsDurableSessionIntegrationTest.class.getResourceAsStream(
            "/db/migration/V67__sc1991_session_ownership.sql")) {
      assertNotNull(input);
      jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    properties = new ReplayProperties();
    properties.setEnabled(true);
    properties.getXrsps().setExecutable("/usr/bin/node");
    properties.getXrsps().setEntrypoint(System.getenv("SC1991_WORKER_ENTRYPOINT"));
    properties
        .getXrsps()
        .setWorkingDirectory(
            java.nio.file.Path.of(System.getenv("SC1991_WORKER_ENTRYPOINT"))
                .getParent()
                .getParent()
                .getParent()
                .getParent()
                .toString());
    properties.getXrsps().setRequestTimeoutMs(60000);
    properties.getXrsps().setMaxStderrBytes(4194304);
    try (var worker = new XrspsKernelProcess(properties.getXrsps(), MAPPER)) {
      worker.start();
      var response = worker.send("capabilities", MAPPER.createObjectNode());
      assertFalse(response.isError());
      capabilities = response.result().path("capabilities");
      assertTrue(capabilities.path("certifiable").asBoolean());
      assertEquals("XRSPS_AUTHORITATIVE", capabilities.path("fidelity").asText());
    }
  }

  @AfterAll
  static void cleanTestSchema() {
    if (jdbc != null && testSchema != null) jdbc.execute("DROP SCHEMA " + testSchema + " CASCADE");
  }

  @BeforeEach
  void setUp() throws Exception {
    jdbc.execute("TRUNCATE xrsps_sessions");
    repository = new XrspsSessionRepository(jdbc, MAPPER, manager);
    manifests = mock(ScenarioManifestRepository.class);
    service = newService();
    scenarioId = UUID.randomUUID();
    var source = new ScenarioManifest();
    source.setPublishState("PUBLISHED");
    source.setCompleteness("COMPLETE");
    source.setGapCount(0);
    source.setFidelity("XRSPS_AUTHORITATIVE");
    source.setManifestJson(manifest());
    when(manifests.findById(scenarioId)).thenReturn(Optional.of(source));
  }

  private XrspsDurableSessionService newService() {
    return new XrspsDurableSessionService(
        new XrspsSessionRepository(jdbc, MAPPER, manager),
        manifests,
        new XrspsSessionWorker(properties, MAPPER),
        MAPPER,
        CANONICAL,
        new com.chromascape.web.replay.security.ReplayOwnershipService(curations()));
  }

  private com.chromascape.web.replay.curation.repository.RecordingCurationRepository curations() {
    var curations =
        mock(com.chromascape.web.replay.curation.repository.RecordingCurationRepository.class);
    var curation = new com.chromascape.web.replay.curation.entity.RecordingCuration();
    curation.setCreatedBy("diagnostic");
    when(curations.findById(any())).thenReturn(Optional.of(curation));
    return curations;
  }

  private JsonNode manifest() throws Exception {
    ObjectNode doc =
        (ObjectNode)
            MAPPER.readTree(
                """
      {"manifestVersion":2,"manifestId":"sc1991-native-test",
       "recording":{"recordingId":"sc1991-test","recordingChecksum":"sha256:recording",
         "debugSessionId":"sc1991-test","debugSessionChecksum":"sha256:session","startTick":0,"endTick":200},
       "curation":{"curationId":"sc1991-test","version":1,"checksum":"sha256:curation"},
       "compiler":{"version":"sc1991-test","schemaVersion":2},
       "determinism":{"seed":"sc1991-atomic","clockOrigin":"2026-01-01T00:00:00.000Z","tickDurationMs":600,"idNamespace":"sc1991-test"},
       "initialState":{"worldId":0,"player":{"position":{"x":3080,"y":3227,"plane":0},
         "skills":{"fishing":{"level":20,"xp":4470}},"inventory":[{"slot":0,"itemId":303,"quantity":1}],"equipment":[]},
         "npcs":[{"stableId":"spot:sc1991:1","npcId":1,"npcTypeId":1530,"position":{"x":3081,"y":3227,"plane":0},
          "spotId":"sea_small_net","spotType":"net","actions":["net"],"size":1}],
         "objects":[],"groundItems":[],"widgets":[],"vars":{}},
       "actions":[{"type":"INTERACT_NPC","targetStableId":"spot:sc1991:1","option":"net"}],
       "expectations":[],"tolerances":{},"controlAllowed":false,"fidelity":"XRSPS_AUTHORITATIVE","fidelityRequirement":"XRSPS_AUTHORITATIVE"}
      """);
    var pins = doc.putObject("runtime");
    var live = capabilities.path("xrsps");
    pins.put("xrspsAdapterVersion", live.path("adapterVersion").asText());
    pins.put("xrspsBuildCommit", live.path("buildCommit").asText());
    pins.put("cacheRevision", live.path("cacheRevision").asText());
    pins.put("contentRevision", live.path("contentRevision").asText());
    doc.put("manifestChecksum", CANONICAL.actionDigest(doc));
    return doc;
  }

  private XrspsRecordedAction action(long tick, long revision, String target) {
    JsonNode payload =
        MAPPER
            .createObjectNode()
            .put("type", "INTERACT_NPC")
            .put("targetStableId", target)
            .put("option", "net");
    return new XrspsRecordedAction(
        tick,
        UUID.randomUUID().toString(),
        "INTERACT_NPC",
        CANONICAL.actionDigest(payload),
        revision,
        payload);
  }

  private UUID running(String key) {
    var created = service.create(XrspsSessionMode.RECORDED_ACTIONS, key, scenarioId.toString());
    service.transition(created.session().getId(), XrspsSession::start);
    return created.session().getId();
  }

  @Test
  void realFishingSurvivesNewServiceAndRepeatedRestoreWithoutDuplicateEffects() {
    UUID id = running("native-fishing-001");
    var action = action(0, 0, "spot:sc1991:1");
    assertTrue(service.run(id, List.of(action)).completed());
    service.transition(id, XrspsSession::pause);
    service.step(id, 10);
    var before = service.observe(id);
    assertTrue(before.durable());
    assertTrue(
        before.domainState().path("player").path("skills").path("fishing").path("xp").asInt()
            > 4470);
    assertTrue(before.domainState().path("player").path("inventory").size() > 1);
    var fresh = newService();
    assertEquals(before, fresh.observe(id));
    fresh.transition(id, XrspsSession::resume);
    var retry = fresh.run(id, List.of(action));
    assertEquals(XrspsReceiptOutcome.DUPLICATE, retry.receipts().get(0).outcome());
    assertEquals(before.domainState(), fresh.observe(id).domainState());
    fresh.transition(id, XrspsSession::pause);
    fresh.step(id, 2);
    assertEquals(12, fresh.get(id).getTick());
    assertEquals(1, fresh.get(id).getObservationRevision());
  }

  @Test
  void rolledBackActionAndCheckpointLeaveNoReceiptAndRetryAppliesExactlyOnce() {
    UUID id = running("native-rollback-001");
    var action = action(0, 0, "spot:sc1991:1");
    var original = repository.get(id).checkpoint;
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(manager)
                .execute(
                    tx -> {
                      assertTrue(service.run(id, List.of(action)).completed());
                      throw new IllegalStateException("Failure before database commit");
                    }));
    var rolledBack = repository.get(id);
    assertEquals(original, rolledBack.checkpoint);
    assertTrue(rolledBack.receipts.isEmpty());
    assertEquals(0, rolledBack.lifecycle.observationRevision());
    var retry = newService().run(id, List.of(action));
    assertEquals(XrspsReceiptOutcome.ACCEPTED, retry.receipts().get(0).outcome());
    assertEquals(1, repository.get(id).receipts.size());
  }

  @Test
  void finishRecordingAdvancesNativeFishingToEndAndRemainsIdempotent() {
    UUID id = running("native-finish-001");
    service.run(id, List.of(action(0, 0, "spot:sc1991:1")));
    assertEquals(0, service.get(id).getTick());
    var result = service.finishRecording(id);
    assertEquals(200, result.tick());
    assertTrue(result.recordingExecuted());
    assertFalse(result.taskVerified());
    assertEquals(XrspsSessionStatus.SUCCEEDED, service.get(id).getStatus());
    assertTrue(
        service
                .observe(id)
                .domainState()
                .path("player")
                .path("skills")
                .path("fishing")
                .path("xp")
                .asLong()
            > 4470);
    assertEquals(result, newService().finishRecording(id));
    assertEquals(1, repository.get(id).receipts.size());
  }

  @Test
  void nativeMismatchLatchesDurablyAndLaterActionsNeverExecute() {
    UUID id = running("native-mismatch-001");
    var bad = action(0, 0, "spot:missing");
    var later = action(1, 1, "spot:sc1991:1");
    var first = service.run(id, List.of(bad, later));
    assertEquals(XrspsRunHaltReason.MISMATCH, first.haltReason());
    assertEquals(XrspsSessionStatus.FAILED, service.get(id).getStatus());
    assertEquals(XrspsReceiptOutcome.REJECTED_SEMANTIC_ACTION, first.firstMismatch().outcome());
    var second = newService().run(id, List.of(bad, later));
    assertEquals(first.firstMismatch(), second.firstMismatch());
    assertEquals(0, second.submitted());
    assertTrue(repository.get(id).receipts.isEmpty());
    assertEquals(0, service.get(id).getTick());
  }

  @Test
  void concurrentCreatesAcrossRepositoryInstancesInitializeOneWorld() throws Exception {
    var count = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(8);
    try {
      var jobs = new java.util.ArrayList<Future<XrspsSessionRepository.Creation>>();
      for (int i = 0; i < 8; i++)
        jobs.add(
            pool.submit(
                () ->
                    new XrspsSessionRepository(jdbc, MAPPER, manager)
                        .create(
                            XrspsSessionMode.RECORDED_ACTIONS,
                            "native-race-001",
                            "scenario:race",
                            s -> {
                              count.incrementAndGet();
                              s.markReady();
                              var doc = new XrspsSessionDocument();
                              doc.lifecycle = s.snapshot();
                              return doc;
                            })));
      UUID winner = jobs.get(0).get(15, TimeUnit.SECONDS).session().getId();
      int created = 0;
      for (var job : jobs) {
        var result = job.get(15, TimeUnit.SECONDS);
        assertEquals(winner, result.session().getId());
        if (result.created()) created++;
      }
      assertEquals(1, created);
      assertEquals(1, count.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void httpRetriesReturnExistingSessionAndExposeRealDomainState() throws Exception {
    var facade = new XrspsSessionExecutionService(service);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new XrspsSessionsController(
                    facade, new com.chromascape.web.replay.security.ReplayPrincipalService()))
            .setControllerAdvice(new ReplayExceptionHandler())
            .build();
    String body =
        MAPPER.writeValueAsString(
            java.util.Map.of(
                "mode",
                "RECORDED_ACTIONS",
                "idempotencyKey",
                "native-http-001",
                "scenarioRef",
                scenarioId.toString()));
    var response =
        mvc.perform(
                post("/api/xrsps/sessions")
                    .principal(() -> "diagnostic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    String id = MAPPER.readTree(response.getContentAsString()).path("id").asText();
    mvc.perform(
            post("/api/xrsps/sessions")
                .principal(() -> "diagnostic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    mvc.perform(get("/api/xrsps/sessions/" + id + "/observation").principal(() -> "diagnostic"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.durable").value(true))
        .andExpect(jsonPath("$.domainState.player.skills.fishing.xp").value(4470));
  }

  @Test
  void hashOnlyAndChangedJournalAreRejectedBeforeWorldMutation() {
    UUID id = running("native-invalid-001");
    var original = repository.get(id).checkpoint;
    var missing =
        new XrspsRecordedAction(0, UUID.randomUUID().toString(), "INTERACT_NPC", "hash", 0);
    assertThrows(ReplayException.class, () -> service.run(id, List.of(missing)));
    assertEquals(original, repository.get(id).checkpoint);
    service.transition(id, XrspsSession::pause);
    var action = action(0, 0, "spot:sc1991:1");
    assertEquals(XrspsRunHaltReason.SESSION_HALTED, service.run(id, List.of(action)).haltReason());
    assertThrows(
        ReplayException.class, () -> service.run(id, List.of(action(0, 0, "spot:missing"))));
    assertEquals(original, repository.get(id).checkpoint);
    assertTrue(repository.get(id).receipts.isEmpty());
  }
}
