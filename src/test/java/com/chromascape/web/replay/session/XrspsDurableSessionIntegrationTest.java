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
import java.util.ArrayList;
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
  private final java.util.List<XrspsSessionWorker> testWorkers = new java.util.ArrayList<>();

  @AfterEach
  void stopTestWorkers() throws Exception {
    testWorkers.forEach(XrspsSessionWorker::shutdown);
    // Archive committed authority, not a viewer reconstruction or private provider transcript.
    var output = java.nio.file.Path.of("build/sc1991-native-journals");
    java.nio.file.Files.createDirectories(output);
    for (var row :
        jdbc.queryForList(
            "SELECT id, idempotency_key, document::text AS document FROM xrsps_sessions")) {
      var document = MAPPER.readTree(row.get("document").toString());
      var evidence = MAPPER.createObjectNode();
      evidence.put("version", 1);
      evidence.put("id", row.get("id").toString());
      evidence.put("fixtureKey", row.get("idempotency_key").toString());
      var committed = evidence.putObject("document");
      for (String field :
          java.util.List.of(
              "lifecycle",
              "manifest",
              "journal",
              "receipts",
              "observation",
              "aiVerification",
              "aiRecoveryCount")) {
        if (document.has(field)) committed.set(field, document.get(field));
      }
      MAPPER
          .writerWithDefaultPrettyPrinter()
          .writeValue(output.resolve(row.get("id") + ".json").toFile(), evidence);
    }
  }

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
    var worker = new XrspsSessionWorker(properties, MAPPER);
    testWorkers.add(worker);
    return new XrspsDurableSessionService(
        new XrspsSessionRepository(jdbc, MAPPER, manager),
        manifests,
        worker,
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
  void rolledBackPacedTickCannotLeakFromWarmWorkerIntoRetry() {
    var created =
        service.create(
            XrspsSessionMode.RECORDED_ACTIONS, "native-warm-rollback", scenarioId.toString());
    UUID id = created.session().getId();
    service.bindPlayback(id, List.of(action(0, 0, "spot:sc1991:1")), "coordinator-a");
    String lease = service.get(id).getLeaseId();
    service.playbackTick(id, lease, "coordinator-a");
    var committed = service.observe(id);
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(manager)
                .execute(
                    status -> {
                      service.playbackTick(id, lease, "coordinator-a");
                      throw new IllegalStateException(
                          "Rollback native tick after candidate checkpoint");
                    }));
    assertEquals(committed, service.observe(id));
    service.playbackTick(id, lease, "coordinator-a");
    var retried = service.observe(id);
    assertEquals(2, retried.tick());
    var doc = repository.get(id);
    try (var fresh = new XrspsSessionWorker(properties, MAPPER).open(doc)) {
      assertEquals(
          CANONICAL.stateHash(retried.domainState()), CANONICAL.stateHash(doc.observation));
    }
    assertEquals(1, repository.get(id).receipts.size());
  }

  @Test
  void pacedOwnershipSeekAndRecoveryPreserveCommittedNativeWorld() {
    var created =
        service.create(
            XrspsSessionMode.RECORDED_ACTIONS, "native-playback-001", scenarioId.toString());
    UUID id = created.session().getId();
    var action = action(0, 0, "spot:sc1991:1");
    service.bindPlayback(id, List.of(action), "coordinator-a");
    String lease = service.get(id).getLeaseId();
    assertThrows(
        ReplayException.class, () -> service.bindPlayback(id, List.of(action), "coordinator-b"));
    service.playbackTick(id, lease, "coordinator-a");
    service.playbackTick(id, lease, "coordinator-a");
    var before = service.observe(id);
    var committed = repository.get(id);
    assertNotNull(committed.clientBootstrap);
    assertEquals(2, before.tick());
    assertEquals(1, committed.receipts.size());
    service.transition(id, XrspsSession::pause);
    service.seek(id, 0);
    var sought = repository.get(id);
    assertEquals(1, sought.seekHistory.size());
    assertTrue(sought.retiredLeases.contains(lease));
    assertThrows(ReplayException.class, () -> service.playbackTick(id, lease, "coordinator-a"));
    service.bindPlayback(id, List.of(action), "coordinator-b");
    service.transition(id, XrspsSession::resume);
    String newLease = service.get(id).getLeaseId();
    service.playbackTick(id, newLease, "coordinator-b");
    service.playbackTick(id, newLease, "coordinator-b");
    assertEquals(
        CANONICAL.stateHash(before.domainState()),
        CANONICAL.stateHash(service.observe(id).domainState()));
    service.transition(id, XrspsSession::reportWorkerLoss);
    service.recover(id, newLease);
    assertEquals(XrspsSessionStatus.PAUSED, service.get(id).getStatus());
    assertTrue(repository.get(id).retiredLeases.contains(newLease));
    assertEquals(
        CANONICAL.stateHash(before.domainState()),
        CANONICAL.stateHash(service.observe(id).domainState()));
  }

  @Test
  void aiJournalReproducesRealNativeFishingWithoutAnotherModelCall() throws Exception {
    var source = manifests.findById(scenarioId).orElseThrow();
    var manifest = (ObjectNode) source.getManifestJson();
    ((ObjectNode) manifest.path("initialState").path("player").path("skills").path("fishing"))
        .put("xp", 5010);
    manifest.remove("manifestChecksum");
    manifest.put("manifestChecksum", CANONICAL.actionDigest(manifest));
    var created =
        service.create(XrspsSessionMode.AI_TASK, "native-ai-journal-001", scenarioId.toString());
    UUID id = created.session().getId();
    service.transition(id, XrspsSession::start);
    var action = action(0, 0, "spot:sc1991:1");
    repository.update(
        id,
        doc -> {
          doc.journal.add(action);
          assertEquals(XrspsReceiptOutcome.ACCEPTED, service.apply(doc, action, false).outcome());
          service.advance(doc, 20, false);
          return null;
        });
    var doc = repository.get(id);
    assertTrue(
        doc.observation.path("player").path("skills").path("fishing").path("level").asInt() >= 21);
    assertEquals(CANONICAL.stateHash(doc.observation), service.verifyAiJournal(doc));
    assertEquals(doc.observation, repository.get(id).observation);
    assertEquals(1, repository.get(id).receipts.size());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "SC1991_AI_MODEL", matches = ".+")
  void configuredProviderCompletesNativeGoalAndReproducesJournalWithoutSecondModelCall()
      throws Exception {
    var source = manifests.findById(scenarioId).orElseThrow();
    var manifest = (ObjectNode) source.getManifestJson();
    ((ObjectNode) manifest.path("initialState").path("player").path("skills").path("fishing"))
        .put("xp", 5010);
    manifest.remove("manifestChecksum");
    manifest.put("manifestChecksum", CANONICAL.actionDigest(manifest));
    var created =
        service.create(XrspsSessionMode.AI_TASK, "configured-native-ai-001", scenarioId.toString());
    UUID id = created.session().getId();
    String model = System.getenv("SC1991_AI_MODEL");
    String url = System.getenv("SC1991_TEST_JDBC_URL") + "?currentSchema=" + testSchema;
    try (var provider =
        new com.chromascape.web.gameplay.provider.runtime.Sc1991ConfiguredProviderHarness(
            url,
            java.nio.file.Files.createTempDirectory("sc1991-configured-ai-"),
            System.getenv("SC1991_AI_EXECUTABLE"),
            model)) {
      var canonical = provider.canonical;
      var coordinator =
          new XrspsAiTaskCoordinator(
              repository,
              service,
              provider.runtime,
              new com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeValidator(
                  new com.chromascape.web.gameplay.contract.GameplayContractValidator(
                      provider.mapper),
                  canonical),
              new com.chromascape.web.replay.security.ReplayPrincipalService(),
              provider.mapper,
              canonical);
      coordinator.start(
          id,
          new XrspsAiTaskCoordinator.FishingGoal(
              com.chromascape.web.gameplay.contract.GameplayContracts.Provider.CODEX,
              model,
              21,
              40,
              300),
          () -> "diagnostic");
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
      boolean recovered = false;
      while (!service.get(id).getStatus().isTerminal() && System.nanoTime() < deadline) {
        coordinator.tick();
        var current = repository.get(id);
        if (!recovered
            && current.aiDecision != null
            && current.aiDecision.isObject()
            && current.lifecycle.status() == XrspsSessionStatus.RUNNING) {
          String hash = CANONICAL.stateHash(current.observation),
              oldLease = current.lifecycle.leaseId();
          coordinator.recover(id, () -> "diagnostic");
          var after = repository.get(id);
          assertEquals(XrspsSessionStatus.PAUSED, after.lifecycle.status());
          assertNotEquals(oldLease, after.lifecycle.leaseId());
          assertEquals(hash, CANONICAL.stateHash(after.observation));
          assertEquals(1, after.journal.size());
          assertThrows(ReplayException.class, () -> service.recover(id, oldLease));
          service.transition(id, XrspsSession::resume);
          recovered = true;
        }
        Thread.sleep(100);
      }
      var doc = repository.get(id);
      java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/sc1991-configured-ai"));
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("build/sc1991-configured-ai/result.json"),
          MAPPER.writeValueAsString(
              java.util.Map.of(
                  "status",
                  doc.lifecycle.status(),
                  "reason",
                  java.util.Objects.toString(doc.lifecycle.terminalReason(), ""),
                  "goal",
                  doc.aiTask,
                  "decision",
                  doc.aiDecision,
                  "verification",
                  doc.aiVerification)));
      assertEquals(
          XrspsSessionStatus.SUCCEEDED,
          doc.lifecycle.status(),
          "Review configured provider turn: " + doc.lifecycle.terminalReason());
      assertTrue(doc.aiVerification.path("taskVerified").asBoolean());
      assertTrue(doc.aiVerification.path("journalReproduced").asBoolean());
      assertEquals(1, doc.journal.size());
      assertTrue(recovered, "Native AI execution was recovered after its committed decision");
      assertEquals(1, doc.aiRecoveryCount);
      assertEquals(1, doc.receipts.size());
      assertTrue(doc.aiDecision.path("transcriptSha256").asText().startsWith("sha256:"));
      var request = doc.aiProviderSession.path("sessionId").asText();
      assertEquals(1, provider.runtime.session(request).usage().providerCalls());
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "SC1991_CLIENT_BUILD", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "SC1991_AI_MODEL", matches = ".+")
  void configuredAiCompletesInTheSameWorldVisibleInTheActualClient() throws Exception {
    var source = manifests.findById(scenarioId).orElseThrow();
    var manifest = (ObjectNode) source.getManifestJson();
    ((ObjectNode) manifest.path("initialState").path("player").path("skills").path("fishing"))
        .put("xp", 5010);
    manifest.remove("manifestChecksum");
    manifest.put("manifestChecksum", CANONICAL.actionDigest(manifest));
    UUID id =
        service
            .create(XrspsSessionMode.AI_TASK, "native-visible-configured-ai", scenarioId.toString())
            .session()
            .getId();
    String model = System.getenv("SC1991_AI_MODEL"),
        url = System.getenv("SC1991_TEST_JDBC_URL") + "?currentSchema=" + testSchema;
    var driver = java.util.concurrent.Executors.newSingleThreadExecutor();
    var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
    try (var provider =
        new com.chromascape.web.gameplay.provider.runtime.Sc1991ConfiguredProviderHarness(
            url,
            java.nio.file.Files.createTempDirectory("sc1991-visible-ai-"),
            System.getenv("SC1991_AI_EXECUTABLE"),
            model)) {
      var coordinator =
          new XrspsAiTaskCoordinator(
              repository,
              service,
              provider.runtime,
              new com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeValidator(
                  new com.chromascape.web.gameplay.contract.GameplayContractValidator(
                      provider.mapper),
                  provider.canonical),
              new com.chromascape.web.replay.security.ReplayPrincipalService(),
              provider.mapper,
              provider.canonical);
      runActualBrowser(
          id,
          "scripts/sc1991-real-client-ai-browser.mjs",
          "build/sc1991-visible-ai",
          command -> {
            if (!command.equals("ai-run") || future.get() != null)
              throw new IllegalArgumentException("Unknown or duplicate AI test control");
            coordinator.start(
                id,
                new XrspsAiTaskCoordinator.FishingGoal(
                    com.chromascape.web.gameplay.contract.GameplayContracts.Provider.CODEX,
                    model,
                    21,
                    40,
                    300),
                () -> "diagnostic");
            future.set(
                driver.submit(
                    () -> {
                      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
                      while (!service.get(id).getStatus().isTerminal()
                          && System.nanoTime() < deadline) {
                        coordinator.tick();
                        try {
                          Thread.sleep(600);
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          return;
                        }
                      }
                    }));
          });
      assertNotNull(future.get());
      future.get().get(60, TimeUnit.SECONDS);
      var doc = repository.get(id);
      assertEquals(XrspsSessionStatus.SUCCEEDED, doc.lifecycle.status());
      assertEquals(
          1,
          provider
              .runtime
              .session(doc.aiProviderSession.path("sessionId").asText())
              .usage()
              .providerCalls());
      assertEquals(1, doc.receipts.size());
      assertEquals(1, doc.journal.size());
    } finally {
      driver.shutdownNow();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "SC1991_CLIENT_BUILD", matches = ".+")
  void actualOsrsClientRendersCommittedNativeWorldWithoutCommandAuthority() throws Exception {
    UUID id = running("native-rendered-client-001");
    service.run(id, List.of(action(0, 0, "spot:sc1991:1")));
    service.transition(id, XrspsSession::pause);
    runActualBrowser(
        id,
        "scripts/sc1991-real-client-browser.mjs",
        "build/sc1991-real-client",
        command -> {
          if (command.equals("advance")) service.step(id, 10);
          else if (command.equals("seek")) service.seek(id, 0);
          else throw new IllegalArgumentException("Unknown test control");
        });
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "SC1991_CLIENT_BUILD", matches = ".+")
  void actualClientRebuildsTheNativeJewelryDestinationAndRestoresItsCharge() throws Exception {
    var source = manifests.findById(scenarioId).orElseThrow();
    var manifest = (ObjectNode) source.getManifestJson();
    ((ObjectNode) manifest.path("initialState")).put("travelProfile", "jewelry-v1");
    ((ObjectNode) manifest.path("initialState").path("player"))
        .set("inventory", MAPPER.readTree("[{\"slot\":0,\"itemId\":11980,\"quantity\":1}]"));
    var actions =
        MAPPER.readTree(
            """
      [{"type":"INTERACT_INVENTORY_ITEM","targetStableId":"inventory:0","slot":0,"itemId":11980,"option":"rub"},
       {"type":"SELECT_DIALOGUE_OPTION","targetStableId":"jewelry:0:11980","option":"Grand Exchange"}]
      """);
    manifest.set("actions", actions);
    manifest.remove("manifestChecksum");
    manifest.put("manifestChecksum", CANONICAL.actionDigest(manifest));
    UUID id = running("native-jewelry-rendered-client-001");
    var journal = new ArrayList<XrspsRecordedAction>();
    for (int index = 0; index < actions.size(); index++) {
      var action = actions.get(index);
      journal.add(
          new XrspsRecordedAction(
              0,
              UUID.randomUUID().toString(),
              action.path("type").asText(),
              CANONICAL.actionDigest(action),
              index,
              action));
    }
    runActualBrowser(
        id,
        "scripts/sc1991-real-client-travel-browser.mjs",
        "build/sc1991-real-travel",
        command -> {
          if (!command.equals("travel"))
            throw new IllegalArgumentException("Unknown travel control");
          service.run(id, journal);
          service.transition(id, XrspsSession::pause);
          service.step(id, 1);
        });
    var before = repository.get(id);
    assertEquals(
        11982, before.observation.path("player").path("inventory").get(0).path("itemId").asInt());
    var recovered = service.recover(id, before.lifecycle.leaseId());
    assertNotEquals(before.lifecycle.leaseId(), recovered.getLeaseId());
    assertEquals(
        CANONICAL.stateHash(before.observation),
        CANONICAL.stateHash(repository.get(id).observation));
  }

  private void runActualBrowser(
      UUID id, String script, String evidence, java.util.function.Consumer<String> controls)
      throws Exception {
    var presentation =
        new XrspsClientPresentationController(
            repository, new com.chromascape.web.replay.security.ReplayPrincipalService());
    var assets = java.nio.file.Path.of(System.getenv("SC1991_CLIENT_BUILD")).toRealPath();
    var packageRoot = assets.getParent().getParent();
    var caches = packageRoot.resolve("server/caches");
    String token = UUID.randomUUID().toString();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var httpExecutor = java.util.concurrent.Executors.newCachedThreadPool();
    server.setExecutor(httpExecutor);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            String content = "application/json";
            if (path.equals("/test/observation")) {
              var doc = repository.get(id);
              var skill = doc.observation.path("player").path("skills").path("fishing");
              body =
                  MAPPER.writeValueAsBytes(
                      java.util.Map.of(
                          "position",
                          doc.observation.path("player").path("position"),
                          "tick",
                          doc.lifecycle.tick(),
                          "status",
                          doc.lifecycle.status(),
                          "fishingXp",
                          skill.path("xp").asLong(),
                          "fishingLevel",
                          skill.path("level").asInt(),
                          "domainStateHash",
                          CANONICAL.stateHash(doc.observation),
                          "journalCount",
                          doc.journal.size(),
                          "receiptCount",
                          doc.receipts.size(),
                          "taskVerified",
                          doc.aiVerification != null
                              && doc.aiVerification.path("taskVerified").asBoolean(),
                          "journalReproduced",
                          doc.aiVerification != null
                              && doc.aiVerification.path("journalReproduced").asBoolean()));
            } else if (path.startsWith("/test/")
                && "POST".equals(exchange.getRequestMethod())
                && token.equals(exchange.getRequestHeaders().getFirst("X-Test-Control"))) {
              controls.accept(path.substring("/test/".length()));
              body = "{}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/xrsps/sessions/" + id + "/presentation")) {
              var query = new java.util.HashMap<String, String>();
              for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                var parts = pair.split("=", 2);
                query.put(parts[0], parts[1]);
              }
              body =
                  MAPPER.writeValueAsBytes(
                      presentation.presentation(
                          id,
                          Long.parseLong(query.getOrDefault("afterSequence", "0")),
                          Boolean.parseBoolean(query.getOrDefault("bootstrap", "false")),
                          Long.parseLong(query.getOrDefault("epoch", "-1")),
                          () -> "diagnostic"));
            } else {
              java.nio.file.Path file;
              if (path.equals("/xrsps-client") || path.equals("/xrsps-client/"))
                file = assets.resolve("index.html");
              else if (path.startsWith("/xrsps-client/caches/"))
                file = caches.resolve(path.substring("/xrsps-client/caches/".length())).normalize();
              else if (path.startsWith("/xrsps-client/"))
                file = assets.resolve(path.substring("/xrsps-client/".length())).normalize();
              else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
              }
              if ((!file.startsWith(assets) && !file.startsWith(caches))
                  || !java.nio.file.Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
              }
              content =
                  file.toString().endsWith(".js")
                      ? "application/javascript"
                      : file.toString().endsWith(".html")
                          ? "text/html"
                          : file.toString().endsWith(".wasm")
                              ? "application/wasm"
                              : java.nio.file.Files.probeContentType(file);
              if (content == null) content = "application/octet-stream";
              long size = java.nio.file.Files.size(file), first = 0, last = size - 1;
              int status = 200;
              exchange.getResponseHeaders().set("Content-Type", content);
              exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
              exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
              exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
              if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
              }
              String range = exchange.getRequestHeaders().getFirst("Range");
              if (range != null) {
                var matcher =
                    java.util.regex.Pattern.compile("bytes=([0-9]+)-([0-9]*)").matcher(range);
                if (!matcher.matches()) throw new IllegalArgumentException("Invalid range");
                first = Long.parseLong(matcher.group(1));
                last =
                    matcher.group(2).isEmpty()
                        ? size - 1
                        : Math.min(size - 1, Long.parseLong(matcher.group(2)));
                if (first < 0 || first > last || first >= size) {
                  exchange.sendResponseHeaders(416, -1);
                  exchange.close();
                  return;
                }
                status = 206;
                exchange
                    .getResponseHeaders()
                    .set("Content-Range", "bytes " + first + "-" + last + "/" + size);
              }
              exchange.sendResponseHeaders(status, last - first + 1);
              try (var input = java.nio.file.Files.newInputStream(file)) {
                input.skipNBytes(first);
                byte[] buffer = new byte[65536];
                long remaining = last - first + 1;
                while (remaining > 0) {
                  int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                  if (read < 0) break;
                  exchange.getResponseBody().write(buffer, 0, read);
                  remaining -= read;
                }
              }
              exchange.close();
              return;
            }
            exchange.getResponseHeaders().set("Content-Type", content);
            exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
            exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          } catch (Exception error) {
            try {
              exchange.sendResponseHeaders(500, -1);
              exchange.close();
            } catch (Exception ignored) {
            }
          }
        });
    server.start();
    try {
      var result =
          new ProcessBuilder(
                  "node",
                  script,
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  id.toString(),
                  packageRoot.toString(),
                  evidence,
                  token)
              .redirectErrorStream(true)
              .redirectOutput(
                  java.nio.file.Path.of("build/sc1991-real-client-browser.log").toFile())
              .start();
      if (!result.waitFor(360, TimeUnit.SECONDS)) {
        result.destroyForcibly();
        fail("Actual client browser timed out");
      }
      assertEquals(
          0,
          result.exitValue(),
          java.nio.file.Files.readString(
              java.nio.file.Path.of("build/sc1991-real-client-browser.log")));
    } finally {
      server.stop(0);
      httpExecutor.shutdownNow();
    }
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
