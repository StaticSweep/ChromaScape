package com.chromascape.web.replay.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.artifact.ArtifactPublicationReceipt;
import com.chromascape.web.replay.artifact.PreparedArtifact;
import com.chromascape.web.replay.artifact.PublishedArtifact;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.compiler.ScenarioManifestInsertService;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.playback.ReplayPlaybackCatalogRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.dto.BranchReplayRunRequest;
import com.chromascape.web.replay.run.dto.CreateReplayRunRequest;
import com.chromascape.web.replay.run.dto.ReplayRunResponse;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import com.chromascape.web.replay.worker.DiagnosticReplayWorkerFactory;
import com.chromascape.web.replay.worker.ReplayExpiredAttemptRecoveryService;
import com.chromascape.web.replay.worker.ReplayLeaseHeartbeat;
import com.chromascape.web.replay.worker.ReplayLeaseRenewalService;
import com.chromascape.web.replay.worker.ReplayRecoveryReportWriter;
import com.chromascape.web.replay.worker.ReplayWorker;
import com.chromascape.web.replay.worker.ReplayWorkerPersistenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Windows-only stress certification for the complete replay/xRSPS delivery.
 *
 * <p>This is intentionally a separate hard gate from {@link ReplayCertificationTest}. It uses the
 * compiled synthetic contract worker for deterministic repeatability and snapshot cases, real
 * PostgreSQL 16 for all durable race cases, and a real content-addressed temporary store. This is
 * synthetic-harness evidence, not authoritative xRSPS game-fidelity evidence. Missing prerequisites
 * fail the class; no assumption or conditional skip is used here.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.main.lazy-initialization=true",
      "spring.datasource.hikari.maximum-pool-size=24",
      "spring.datasource.hikari.connection-timeout=120000",
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ContextConfiguration(classes = ReplayStressCertificationTest.StressSliceApplication.class)
@ActiveProfiles("replay-stress-certification")
@Import({
  ReplayStressCertificationTest.StressServices.class,
  ReplayCertificationTest.CertificationServices.class,
  ReplayPlaybackCatalogRepository.class
})
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class ReplayStressCertificationTest {

  @Configuration(proxyBeanMethods = false)
  @Profile("replay-stress-certification")
  @EnableAutoConfiguration
  @EntityScan(basePackages = "com.chromascape.web.replay")
  @EnableJpaRepositories(basePackages = "com.chromascape.web.replay")
  static class StressSliceApplication {}

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("replay_stress_certification")
          .withUsername("test")
          .withPassword("test")
          // Docker Desktop/WSL may race log-consumer attachment; readiness is the listening port.
          .waitingFor(HostPortWaitStrategy.postgres());

  private static final String[] REPLAY_MIGRATIONS = {
    "V11__replay_curation_and_manifest.sql",
    "V12__replay_suites_and_runs.sql",
    "V13__replay_events_checkpoints_and_hashes.sql",
    "V14__replay_mismatches_reports_and_artifacts.sql",
    "V15__replay_schema_alignment.sql",
    "V17__replay_artifact_reference_reuse.sql",
    "V18__replay_checkpoint_artifact_checksum.sql",
    "V19__reconcile_replay_checkpoint_artifacts.sql",
    "V20__replay_finalization_reservation.sql",
    "V25__replay_v2_metadata.sql",
    "V26__replay_v2_request_identity.sql",
    "V30__replay_pre_execution_reports.sql",
    "V31__replay_playback_catalog_pagination.sql"
  };

  private static final Path MIGRATION_DIRECTORY = createMigrationDirectory();
  private static final String WORKER_EXECUTABLE = envOrDefault("REPLAY_XRSPS_EXECUTABLE", "node");
  private static final String WORKER_ENTRYPOINT =
      envOrDefault(
          "REPLAY_XRSPS_ENTRYPOINT",
          "C:\\Dev\\IdeaProjects\\xrsps-typescript\\packages\\replay-kernel\\dist\\worker\\main.js");
  private static final String WORKER_WORKING_DIRECTORY =
      envOrDefault("REPLAY_XRSPS_WORKING_DIRECTORY", "C:\\Dev\\IdeaProjects\\xrsps-typescript");

  private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
  private static final String INVOCATION_ID =
      systemPropertyOrDefault("replay.certification.invocation-id", UUID.randomUUID().toString());
  private static final Path CERTIFICATION_OUTPUT_DIRECTORY =
      Path.of(
          systemPropertyOrDefault(
              "replay.certification.output-dir",
              Path.of("build", "replay-certification", "staging", INVOCATION_ID)
                  .toAbsolutePath()
                  .toString()));
  private static final Path RUNTIME_FIXTURE_ROOT =
      Path.of("build", "replay-certification", "runtime-fixtures", INVOCATION_ID).toAbsolutePath();
  private static RuntimeIdentity liveIdentity;
  private static JsonNode liveCapabilities;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            POSTGRES.getJdbcUrl()
                + "?options=-c%20lock_timeout%3D5000%20-c%20statement_timeout%3D30000");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "spring.flyway.locations", () -> "filesystem:" + MIGRATION_DIRECTORY.toAbsolutePath());
  }

  @Autowired ReplayRunRepository runRepository;
  @Autowired ReplayAttemptRepository attemptRepository;
  @Autowired ReplayCheckpointRepository checkpointRepository;
  @Autowired ReplayEventRepository eventRepository;
  @Autowired ReplayMismatchRepository mismatchRepository;
  @Autowired StateHashSampleRepository hashSampleRepository;
  @Autowired ArtifactRefRepository artifactRefRepository;
  @Autowired ScenarioManifestRepository manifestRepository;
  @Autowired ReplayReportRepository reportRepository;
  @Autowired ReplayRunService runService;
  @Autowired ReplayWorkerPersistenceService persistence;
  @Autowired ReplayLeaseRenewalService leaseRenewalService;
  @Autowired ReplayExpiredAttemptRecoveryService recoveryService;
  @Autowired ReplayRecoveryReportWriter reportWriter;
  @Autowired ReplayReportService reportService;
  @Autowired ScenarioManifestInsertService manifestInsertService;
  @Autowired ReplayPlaybackCatalogRepository catalogRepository;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;

  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalProjectionService projection = new CanonicalProjectionService();
  private final List<UUID> createdRunIds = new ArrayList<>();

  private Path artifactRoot;
  private ReplayArtifactService artifactService;
  private ReplayProperties properties;
  private ReplayLeaseHeartbeat heartbeat;
  private ReplayWorker worker;
  private TransactionTemplate tx;

  @BeforeAll
  static void requireWindowsAndSyntheticWorker() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("win")) {
      throw new IllegalStateException(
          "replayStressCertificationTest is Windows-only; refusing to run on '" + os + "'");
    }
    if ("1".equals(System.getenv("XRSPS_ALLOW_DEV_IDENTITY"))) {
      throw new IllegalStateException(
          "XRSPS_ALLOW_DEV_IDENTITY=1 is forbidden for stress certification");
    }
    Path entrypoint = Path.of(WORKER_ENTRYPOINT);
    if (!Files.isRegularFile(entrypoint)) {
      throw new IllegalStateException("Missing certifiable xRSPS worker: " + WORKER_ENTRYPOINT);
    }
    CapabilityCapture capture = fetchCapabilities();
    liveCapabilities = capture.capabilities();
    liveIdentity = capture.identity();
    validateCapabilityDocument(liveCapabilities, liveIdentity);
    verifyRuntimeInputs(Path.of(WORKER_WORKING_DIRECTORY));
    recordEnvironment(capture.capabilities());
  }

  @BeforeEach
  void setUp() throws IOException {
    artifactRoot = Files.createTempDirectory("replay-stress-artifacts-");
    properties = certificationProperties(artifactRoot);
    artifactService = new ReplayArtifactService(artifactRefRepository, properties);
    // Use the Spring-managed renewal service so its REQUIRES_NEW transaction boundary remains
    // active on the scheduled heartbeat thread. Constructing this service directly bypasses the
    // transactional proxy and poisons a long-running failure fixture at the lease boundary.
    heartbeat = new ReplayLeaseHeartbeat(leaseRenewalService, properties);
    worker = newWorker(new ReplayArtifactService(artifactRefRepository, properties));
    tx = new TransactionTemplate(transactionManager);
  }

  private ReplayWorker newWorker(ReplayArtifactService service) {
    return DiagnosticReplayWorkerFactory.create(
        runRepository,
        attemptRepository,
        runService,
        manifestRepository,
        checkpointRepository,
        artifactRefRepository,
        mismatchRepository,
        persistence,
        heartbeat,
        recoveryService,
        service,
        new com.chromascape.web.replay.mismatch.MismatchEngine(
            mismatchRepository, new CanonicalProjectionService()),
        reportService,
        new CanonicalProjectionService(),
        properties,
        mapper);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (worker != null) {
      worker.close();
    }
    if (heartbeat != null) {
      heartbeat.destroy();
    }
    if (artifactRoot != null && Files.exists(artifactRoot)) {
      try (var paths = Files.walk(artifactRoot)) {
        paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @org.junit.jupiter.api.AfterAll
  static void removeMigrationDirectory() throws IOException {
    if (MIGRATION_DIRECTORY == null || !Files.exists(MIGRATION_DIRECTORY)) {
      return;
    }
    try (var paths = Files.walk(MIGRATION_DIRECTORY)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  @Test
  @Order(1)
  void deterministicManifestProducesOneHundredIdenticalRuns() throws Exception {
    long processCountBefore = countProcessesContaining("replay-kernel");
    UUID manifestId = seedPublishedManifest("determinism", 4, liveIdentity.xrspsBuildCommit());
    long startedAtNanos = System.nanoTime();
    List<RunEvidence> evidence = new ArrayList<>();

    for (int index = 0; index < 100; index++) {
      ReplayRunResponse created = createRun(manifestId, "determinism-" + index);
      ReplayRun terminal = awaitTerminal(created.runId(), Duration.ofSeconds(90));
      assertEquals(ReplayRunState.SUCCEEDED, terminal.getState());
      assertEquals(ReplayOutcome.PASSED, terminal.getOutcome());
      evidence.add(readEvidence(terminal));
    }

    RunEvidence baseline = evidence.get(0);
    for (int index = 1; index < evidence.size(); index++) {
      assertEquals(baseline.chainHash(), evidence.get(index).chainHash(), "chain hash " + index);
      assertEquals(
          baseline.stateHashes(), evidence.get(index).stateHashes(), "state hashes " + index);
      assertEquals(baseline.eventPayloadHashes(), evidence.get(index).eventPayloadHashes());
      assertEquals(baseline.checkpointRows(), evidence.get(index).checkpointRows());
      assertEquals(baseline.artifactRows(), evidence.get(index).artifactRows());
      assertEquals(
          baseline.artifactRows(),
          evidence.get(index).finalizedArtifactReadback().stream()
              .filter(row -> !row.startsWith(ArtifactKind.REPORT.name() + ":"))
              .toList(),
          "non-report artifact readback " + index);
      assertEquals(baseline.trace(), evidence.get(index).trace());
    }

    assertEquals(100, evidence.size());
    assertNoActiveLeasesOrStaging(createdRunIds);
    waitForNoAdditionalProcesses(processCountBefore, "replay-kernel");

    ObjectNode output = mapper.createObjectNode();
    output.put("invocationId", INVOCATION_ID);
    output.put("scenario", "determinism-100");
    output.put("evidenceClass", "synthetic-harness-repeatability");
    output.put("fidelity", liveCapabilities.path("fidelity").asText());
    output.put("runs", evidence.size());
    output.put("runCount", evidence.size());
    output.put(
        "passCount",
        evidence.stream()
            .filter(run -> run.chainHash() != null && !run.chainHash().isBlank())
            .count());
    output.put("failureCount", 0);
    output.put(
        "elapsedDurationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    ObjectNode workerIdentity = output.putObject("workerIdentity");
    workerIdentity.put("adapterVersion", liveIdentity.xrspsAdapterVersion());
    workerIdentity.put("buildCommit", liveIdentity.xrspsBuildCommit());
    workerIdentity.put("cacheRevision", liveIdentity.cacheRevision());
    workerIdentity.put("contentRevision", liveIdentity.contentRevision());
    ScenarioManifest manifest = manifestRepository.findById(manifestId).orElseThrow();
    output.put("manifestId", manifestId.toString());
    output.put("manifestChecksum", manifest.getManifestChecksum());
    output.put("postgresImage", POSTGRES.getDockerImageName());
    output.put("terminalState", ReplayRunState.SUCCEEDED.name());
    output.put("outcome", ReplayOutcome.PASSED.name());
    output.put("chainHash", baseline.chainHash());
    output.put("finalChainHash", baseline.chainHash());
    output.set("stateHashes", mapper.valueToTree(baseline.stateHashes()));
    output.set("orderedStateHashes", mapper.valueToTree(baseline.stateHashes()));
    output.set("eventPayloadHashes", mapper.valueToTree(baseline.eventPayloadHashes()));
    output.set("orderedEventPayloadHashes", mapper.valueToTree(baseline.eventPayloadHashes()));
    output.set("checkpointRows", mapper.valueToTree(baseline.checkpointRows()));
    output.set("artifactRows", mapper.valueToTree(baseline.artifactRows()));
    output.set(
        "nonReportArtifactChecksums",
        mapper.valueToTree(
            baseline.artifactRows().stream()
                .map(row -> row.substring(row.indexOf(':') + 1, row.lastIndexOf(':')))
                .toList()));
    output.set(
        "finalizedArtifactReadback", mapper.valueToTree(baseline.finalizedArtifactReadback()));
    output.set("canonicalTrace", baseline.trace());
    output.set("canonicalTransientFreeTrace", baseline.trace());
    output.put("processCountBefore", processCountBefore);
    output.put("processCountAfter", countProcessesContaining("replay-kernel"));
    output.put(
        "activeLeaseCountAfter",
        scalarCount(
            "SELECT COUNT(*) FROM replay_attempts WHERE state = 'RUNNING' OR lease_expires_at IS NOT NULL"));
    output.put(
        "stagingReferenceCountAfter",
        scalarCount("SELECT COUNT(*) FROM artifact_refs WHERE state = 'STAGING'"));
    output.put("reportArtifactsExcludedBecauseRunScoped", true);
    output.put(
        "reportArtifactNote",
        "REPORT bytes contain run id and PostgreSQL terminal time; all non-report content-addressed artifacts are equal.");
    writeCertificationJson("determinism-100.json", output);
  }

  @Test
  @Order(2)
  void failureInjectionBatteryFailsClosedAndLeavesNoFalseSuccess() throws Exception {
    List<ObjectNode> results = new ArrayList<>();
    results.add(
        runRepeatedFixtureCase(
            "exit-before-capabilities", EXIT_BEFORE_CAPABILITIES, "capabilities"));
    results.add(runRepeatedFixtureCase("exit-during-reset", EXIT_DURING_RESET, "reset"));
    results.add(
        runRepeatedFixtureCase(
            "exit-between-step-and-snapshot", EXIT_BETWEEN_STEP_AND_SNAPSHOT, "snapshot"));
    results.add(runRepeatedFixtureCase("malformed-stdout", MALFORMED_STDOUT, "malformed"));
    results.add(runRepeatedFixtureCase("request-timeout", REQUEST_TIMEOUT, "timeout"));

    results.add(runArtifactFailure("missing-staged-artifact", false));
    results.add(runArtifactFailure("corrupt-staged-artifact", true));
    results.add(runRealSnapshotFailure("snapshot-checksum-mismatch", false));
    results.add(runRealSnapshotFailure("snapshot-runtime-identity-mismatch", true));
    results.add(runManifestPinFailure());
    results.add(runFinalizationUniquenessConflict());

    assertEquals(11, results.size());
    for (ObjectNode result : results) {
      assertTrue(result.path("deterministic").asBoolean(false), result.toString());
      assertFalse(result.path("falseSuccess").asBoolean(true), result.toString());
      assertTrue(result.path("processesReleased").asBoolean(false), result.toString());
    }
    ObjectNode output = mapper.createObjectNode();
    output.put("invocationId", INVOCATION_ID);
    output.put("scenario", "failure-injection");
    output.put("evidenceClass", "synthetic-harness-failure-safety");
    output.put("fidelity", liveCapabilities.path("fidelity").asText());
    output.put("cases", results.size());
    output.set("results", mapper.valueToTree(results));
    writeCertificationJson("failure-injection.json", output);
  }

  @Test
  @Order(3)
  void postgresRaceBatteryHasOneWinnerAndStableContinuation() throws Exception {
    List<ObjectNode> scenarios = new ArrayList<>();
    scenarios.add(runClaimRaces());
    scenarios.add(runExpiredAttemptRecoveryRaces());
    scenarios.add(runManifestChecksumRaces());
    scenarios.add(runArtifactPublisherRaces());
    scenarios.add(runRecoveryVersusNormalCompletionRaces());
    scenarios.add(runReportWriterRaces());
    scenarios.add(runCatalogContinuationRaces());

    for (ObjectNode scenario : scenarios) {
      assertEquals(25, scenario.path("repetitions").asInt());
      assertTrue(scenario.path("passed").asBoolean(false), scenario.toString());
    }
    assertNoActiveLeasesOrStaging(createdRunIds);
    ObjectNode output = mapper.createObjectNode();
    output.put("invocationId", INVOCATION_ID);
    output.put("scenario", "postgres-races");
    output.put("evidenceClass", "replay-persistence-concurrency");
    output.put("fidelity", liveCapabilities.path("fidelity").asText());
    output.put("postgresImage", POSTGRES.getDockerImageName());
    output.set("scenarios", mapper.valueToTree(scenarios));
    writeCertificationJson("postgres-races.json", output);
  }

  // ── worker identity and environment ──

  private static CapabilityCapture fetchCapabilities() {
    ReplayProperties properties = new ReplayProperties();
    ReplayProperties.Xrsps xrsps = properties.getXrsps();
    xrsps.setExecutable(WORKER_EXECUTABLE);
    xrsps.setEntrypoint(WORKER_ENTRYPOINT);
    xrsps.setWorkingDirectory(WORKER_WORKING_DIRECTORY);
    xrsps.setStartupTimeoutMs(20_000);
    xrsps.setRequestTimeoutMs(20_000);
    XrspsKernelProcess kernel = new XrspsKernelProcess(xrsps, STATIC_MAPPER);
    try {
      kernel.start();
      KernelMessage response = kernel.send("capabilities", null);
      if (response.isError()) {
        throw new IllegalStateException("Capability request failed: " + response.error());
      }
      JsonNode capability = response.result().path("capabilities");
      if (capability.isMissingNode()) {
        capability = response.result();
      }
      JsonNode xrspsNode = capability.path("xrsps");
      RuntimeIdentity identity =
          new RuntimeIdentity(
              xrspsNode.path("adapterVersion").asText(null),
              xrspsNode.path("buildCommit").asText(null),
              xrspsNode.path("cacheRevision").asText(null),
              xrspsNode.path("contentRevision").asText(null));
      return new CapabilityCapture(capability, identity);
    } finally {
      kernel.close();
    }
  }

  private static void validateCapabilityDocument(JsonNode capability, RuntimeIdentity identity) {
    if (!capability.path("certifiable").asBoolean(false)) {
      throw new IllegalStateException("certifiable worker advertised certifiable=false");
    }
    if (!capability.path("modes").path("causalSimulation").asBoolean(false)) {
      throw new IllegalStateException("certifiable worker does not offer causalSimulation");
    }
    if (capability.path("modes").path("policyReplay").asBoolean(false)
        || capability.path("controlAllowed").asBoolean(false)
        || capability.path("openRuneAvailable").asBoolean(false)) {
      throw new IllegalStateException("forbidden xRSPS capability surface is enabled");
    }
    if (capability.path("modes").path("observedPlayback").asBoolean(true)) {
      throw new IllegalStateException("observed playback belongs to ChromaScape, not the worker");
    }
    String fidelity = capability.path("fidelity").asText(null);
    if (!"SYNTHETIC_CONTRACT".equals(fidelity)) {
      throw new IllegalStateException(
          "the pinned worker must be truthfully classified as SYNTHETIC_CONTRACT, got " + fidelity);
    }
    JsonNode supportedActions = capability.get("supportedActions");
    if (!supportedActionsEqualsV1(supportedActions)) {
      throw new IllegalStateException(
          "worker supportedActions must be exactly [WAIT_UNTIL_TICK, INTERACT_NPC]");
    }
    for (String field :
        List.of("network", "networking", "policy", "browser", "account", "liveGame")) {
      if (capability.path(field).isBoolean() && capability.path(field).asBoolean()) {
        throw new IllegalStateException("forbidden capability surface is enabled: " + field);
      }
    }
    if (identity.xrspsBuildCommit() == null
        || !identity.xrspsBuildCommit().matches("[0-9a-f]{40}")) {
      throw new IllegalStateException("worker buildCommit is not a 40-character lowercase commit");
    }
    if (identity.cacheRevision() == null
        || !identity.cacheRevision().matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException("worker cacheRevision is not a content address");
    }
    if (identity.contentRevision() == null
        || !identity.contentRevision().matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException("worker contentRevision is not a content address");
    }
    String root = gitLine(WORKER_WORKING_DIRECTORY, "rev-parse", "--show-toplevel");
    String head = gitLine(root, "rev-parse", "HEAD");
    if (!head.equals(identity.xrspsBuildCommit())) {
      throw new IllegalStateException(
          "worker identity is stale: " + identity.xrspsBuildCommit() + " != " + head);
    }
    if (!gitLine(root, "status", "--porcelain").isBlank()) {
      throw new IllegalStateException("xRSPS certification worktree is dirty: " + root);
    }
  }

  private static boolean supportedActionsEqualsV1(JsonNode actions) {
    return actions != null
        && actions.isArray()
        && actions.size() == 2
        && "WAIT_UNTIL_TICK".equals(actions.get(0).asText(null))
        && "INTERACT_NPC".equals(actions.get(1).asText(null));
  }

  /**
   * The generated identity is only certifiable when these inputs were inventoried. Recheck their
   * presence at the certification boundary so a stale generated file cannot hide a missing cache or
   * content input in the worktree used by the worker.
   */
  private static void verifyRuntimeInputs(Path workerRoot) {
    requireNonEmptyDirectory(workerRoot.resolve("server").resolve("cache"), "server/cache");
    requireNonEmptyDirectory(
        workerRoot.resolve("packages").resolve("replay-kernel").resolve("src").resolve("actions"),
        "replay-kernel/src/actions");
    requireNonEmptyDirectory(
        workerRoot.resolve("packages").resolve("replay-kernel").resolve("src").resolve("world"),
        "replay-kernel/src/world");
  }

  private static void requireNonEmptyDirectory(Path directory, String label) {
    if (!Files.isDirectory(directory)) {
      throw new IllegalStateException("Missing xRSPS certification input directory: " + label);
    }
    try (var paths = Files.walk(directory)) {
      if (paths.noneMatch(Files::isRegularFile)) {
        throw new IllegalStateException("Empty xRSPS certification input directory: " + label);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not inspect xRSPS certification input: " + label, e);
    }
  }

  private static void recordEnvironment(JsonNode rawCapability) {
    try {
      Path directory = CERTIFICATION_OUTPUT_DIRECTORY;
      Files.createDirectories(directory);
      ObjectNode capabilities = rawCapability.deepCopy();
      capabilities.put("invocationId", INVOCATION_ID);
      Files.writeString(
          directory.resolve("capabilities.json"), STATIC_MAPPER.writeValueAsString(capabilities));
      Files.writeString(
          directory.resolve("identity.properties"),
          "invocationId="
              + INVOCATION_ID
              + "\n"
              + "xrsps.commit="
              + liveIdentity.xrspsBuildCommit()
              + "\n"
              + "xrsps.adapterVersion="
              + liveIdentity.xrspsAdapterVersion()
              + "\n"
              + "xrsps.cacheRevision="
              + liveIdentity.cacheRevision()
              + "\n"
              + "xrsps.contentRevision="
              + liveIdentity.contentRevision()
              + "\n"
              + "postgres.image="
              + POSTGRES.getDockerImageName()
              + "\n"
              + "java.version="
              + System.getProperty("java.version")
              + "\n"
              + "java.vendor="
              + System.getProperty("java.vendor")
              + "\n"
              + "java.osArch="
              + System.getProperty("os.arch")
              + "\n"
              + "java.dataModel="
              + System.getProperty("sun.arch.data.model", "unknown")
              + "\n"
              + "node.executable="
              + WORKER_EXECUTABLE
              + "\n"
              + "node.version="
              + diagnosticCommand(WORKER_EXECUTABLE, "--version")
              + "\n"
              + "node.path="
              + diagnosticCommand("where", "node")
              + "\n"
              + "node.execPath="
              + diagnosticCommand(WORKER_EXECUTABLE, "-p", "process.execPath")
              + "\n"
              + "node.arch="
              + diagnosticCommand(WORKER_EXECUTABLE, "-p", "process.arch")
              + "\n"
              + "gradle.version="
              + diagnosticCommand("gradlew.bat", "--version")
              + "\n"
              + "path="
              + System.getenv("PATH")
              + "\n"
              + "working.directory="
              + Path.of("").toAbsolutePath()
              + "\n"
              + "temporary.directory.raw="
              + System.getProperty("java.io.tmpdir")
              + "\n"
              + "temporary.directory.form="
              + Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
              + "\n");
    } catch (Exception e) {
      throw new IllegalStateException("Could not record stress-certification environment", e);
    }
  }

  private static String gitLine(String directory, String... arguments) {
    String[] command = new String[arguments.length + 3];
    command[0] = "git";
    command[1] = "-C";
    command[2] = directory;
    System.arraycopy(arguments, 0, command, 3, arguments.length);
    return diagnosticCommand(command);
  }

  private static String diagnosticCommand(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "Diagnostic command timed out: " + String.join(" ", command));
      }
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "Diagnostic command failed ("
                + process.exitValue()
                + "): "
                + String.join(" ", command));
      }
      return output.replace('\n', '|');
    } catch (IOException e) {
      throw new IllegalStateException(
          "Diagnostic command could not start: " + String.join(" ", command), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Diagnostic command interrupted", e);
    }
  }

  // ── deterministic execution ──

  private RunEvidence readEvidence(ReplayRun run) {
    ReplayReport report = reportRepository.findByRunId(run.getId()).orElseThrow();
    List<StateHashSample> states =
        hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(run.getId());
    List<ReplayEvent> events = eventRepository.findByRunIdOrderByOrdinalAsc(run.getId());
    List<ReplayCheckpoint> checkpoints =
        checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(run.getId(), run.getBranchKey());
    List<ArtifactRef> refs = artifactRefRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
    assertFalse(refs.isEmpty(), "successful run must have artifact references");
    assertTrue(
        refs.stream().allMatch(ref -> ArtifactRef.STATE_FINALIZED.equals(ref.getState())),
        "every successful-run artifact reference must be FINALIZED");

    List<String> stateHashes =
        states.stream()
            .map(
                sample ->
                    sample.getId().getTick()
                        + ":"
                        + sample.getId().getSequence()
                        + ":"
                        + sample.getStateHash())
            .toList();
    List<String> eventPayloadHashes =
        events.stream()
            .map(
                event ->
                    event.getOrdinal()
                        + ":"
                        + event.getTick()
                        + ":"
                        + event.getSequence()
                        + ":"
                        + event.getEventType()
                        + ":"
                        + projection.sha256Hex(projection.canonicalJsonBytes(event.getPayload())))
            .toList();
    List<Map<String, Object>> checkpointRows =
        checkpoints.stream()
            .map(
                checkpoint -> {
                  Map<String, Object> row = new HashMap<>();
                  row.put("tick", checkpoint.getTick());
                  row.put("actionIndex", checkpoint.getActionIndex());
                  row.put("snapshotChecksum", checkpoint.getSnapshotChecksum());
                  row.put("snapshotArtifactChecksum", checkpoint.getSnapshotArtifactChecksum());
                  row.put("stateHash", checkpoint.getStateHash());
                  row.put("chainHash", checkpoint.getChainHash());
                  return row;
                })
            .toList();
    List<String> artifactRows =
        refs.stream()
            .filter(ref -> !ArtifactKind.REPORT.equals(ref.getKind()))
            .map(ref -> ref.getKind().name() + ":" + ref.getChecksum() + ":" + ref.getSizeBytes())
            .sorted()
            .toList();
    List<String> finalizedArtifactReadback =
        refs.stream().map(this::readAndVerifyFinalizedArtifact).sorted().toList();
    String trace =
        refs.stream()
            .filter(ref -> ref.getKind() == ArtifactKind.TRACE)
            .findFirst()
            .map(ref -> canonicalTransientFree(artifactService.readFinalized(ref)))
            .orElseThrow();
    JsonNode traceDocument;
    try {
      traceDocument = mapper.readTree(trace);
    } catch (IOException e) {
      throw new IllegalStateException("Trace artifact is not valid JSON", e);
    }
    return new RunEvidence(
        run.getId(),
        report.getFinalChainHash(),
        stateHashes,
        eventPayloadHashes,
        checkpointRows,
        artifactRows,
        finalizedArtifactReadback,
        traceDocument);
  }

  /** Reads every finalized artifact and independently recomputes its content address. */
  private String readAndVerifyFinalizedArtifact(ArtifactRef ref) {
    byte[] bytes = artifactService.readFinalized(ref);
    assertEquals(ref.getSizeBytes(), bytes.length, "artifact size readback: " + ref.getKind());
    assertEquals(
        ref.getChecksum(),
        projection.sha256Hex(bytes),
        "artifact checksum readback: " + ref.getKind());
    return ref.getKind().name() + ":" + ref.getChecksum() + ":" + bytes.length;
  }

  private String canonicalTransientFree(byte[] bytes) {
    try {
      return projection.canonicalJson(removeTransientFields(mapper.readTree(bytes)));
    } catch (IOException e) {
      throw new IllegalStateException("Trace artifact is not valid JSON", e);
    }
  }

  private JsonNode removeTransientFields(JsonNode node) {
    if (node == null || node.isNull()) {
      return mapper.nullNode();
    }
    if (node.isArray()) {
      ArrayNode result = mapper.createArrayNode();
      node.forEach(child -> result.add(removeTransientFields(child)));
      return result;
    }
    if (!node.isObject()) {
      return node.deepCopy();
    }
    ObjectNode result = mapper.createObjectNode();
    node.fields()
        .forEachRemaining(
            field -> {
              String name = field.getKey().toLowerCase(Locale.ROOT);
              if (!Set.of(
                      "runid",
                      "attemptid",
                      "createdat",
                      "startedat",
                      "terminalat",
                      "finalizedat",
                      "timestamp",
                      "persistedat")
                  .contains(name)) {
                result.set(field.getKey(), removeTransientFields(field.getValue()));
              }
            });
    return result;
  }

  // ── failure injection ──

  private ObjectNode runRepeatedFixtureCase(String name, String source, String sequence)
      throws Exception {
    long before = countProcessesContaining(name);
    Path script = writeFixture(name, source);
    FailureObservation first = runFixture(script, sequence);
    FailureObservation second = runFixture(script, sequence);
    assertNotEquals("NO_FAILURE", first.code(), "fixture must fail: " + name);
    assertNotEquals("NO_FAILURE", second.code(), "fixture must fail on repeat: " + name);
    assertEquals(first.code(), second.code(), "fixture error must be deterministic: " + name);
    DurableFailureObservation durableFirst = runWorkerFixture(script, name + "-durable-1");
    DurableFailureObservation durableSecond = runWorkerFixture(script, name + "-durable-2");
    if ("exit-before-capabilities".equals(name)) {
      assertFalse(
          first.message().contains(SECRET_STDERR_MARKER),
          "raw stderr must not enter the kernel ReplayException");
      assertFalse(
          durableFirst.run().getTerminalReason().contains(SECRET_STDERR_MARKER),
          "raw stderr must not enter the durable terminal reason");
      assertFalse(
          durableFirst.report().getTerminalReason().contains(SECRET_STDERR_MARKER),
          "raw stderr must not enter the durable report reason");
      assertFalse(
          durableFirst.report().getReportJson().contains(SECRET_STDERR_MARKER),
          "raw stderr must not enter the durable report JSON");
      assertFalse(
          durableSecond.report().getReportJson().contains(SECRET_STDERR_MARKER),
          "raw stderr must not enter the repeated durable report JSON");
    }
    assertEquals(
        durableFirst.run().getState(),
        durableSecond.run().getState(),
        "durable fixture state must be deterministic: " + name);
    assertEquals(
        durableFirst.run().getTerminalReason(),
        durableSecond.run().getTerminalReason(),
        "durable fixture reason must be deterministic: " + name);
    assertEquals(
        durableFirst.report().getOutcome(),
        durableSecond.report().getOutcome(),
        "durable fixture report outcome must be deterministic: " + name);
    waitForNoAdditionalProcesses(before, name);
    assertNoActiveLeasesOrStaging(List.of(durableFirst.run().getId(), durableSecond.run().getId()));
    ObjectNode result = mapper.createObjectNode();
    result.put("case", name);
    result.put("error", first.code());
    result.put("secondError", second.code());
    result.put(
        "deterministic",
        first.code().equals(second.code())
            && durableFirst.run().getState() == durableSecond.run().getState()
            && durableFirst
                .run()
                .getTerminalReason()
                .equals(durableSecond.run().getTerminalReason())
            && durableFirst.report().getOutcome() == durableSecond.report().getOutcome());
    result.put("falseSuccess", "NO_FAILURE".equals(first.code()));
    result.put("processesReleased", !hasProcessContaining(name));
    result.put("runState", durableFirst.run().getState().name());
    result.put("secondRunState", durableSecond.run().getState().name());
    result.put("terminalReason", durableFirst.run().getTerminalReason());
    result.put("reportPresent", true);
    result.put("secondReportPresent", true);
    result.put("reportOutcome", durableFirst.report().getOutcome().name());
    result.put("secondReportOutcome", durableSecond.report().getOutcome().name());
    result.put("leaseReleased", durableFirst.leaseReleased() && durableSecond.leaseReleased());
    result.put(
        "stagingReferencesEmpty",
        durableFirst.stagingReferencesEmpty() && durableSecond.stagingReferencesEmpty());
    result.put(
        "finalizedReferencesValid",
        durableFirst.finalizedReferencesValid() && durableSecond.finalizedReferencesValid());
    result.put("finalizedReferenceCount", durableFirst.finalizedReferenceCount());
    result.put("secondFinalizedReferenceCount", durableSecond.finalizedReferenceCount());
    result.put(
        "recoveryOrFailClosed",
        durableFirst.run().isTerminal()
            && durableSecond.run().isTerminal()
            && durableFirst.report().getOutcome() != ReplayOutcome.PASSED
            && durableSecond.report().getOutcome() != ReplayOutcome.PASSED);
    result.put("fixture", script.toAbsolutePath().toString());
    return result;
  }

  /**
   * Drives the same failure fixture through the real worker so the durable fail-closed contract is
   * checked in addition to the direct kernel protocol observation above.
   */
  private DurableFailureObservation runWorkerFixture(Path script, String key) {
    ReplayProperties.Xrsps xrsps = properties.getXrsps();
    String originalEntrypoint = xrsps.getEntrypoint();
    String originalWorkingDirectory = xrsps.getWorkingDirectory();
    try {
      xrsps.setEntrypoint(script.toAbsolutePath().toString());
      xrsps.setWorkingDirectory(script.toAbsolutePath().getParent().toString());
      int actionCount = key.contains("exit-between-step-and-snapshot") ? 1 : 0;
      ReplayRunResponse created =
          createRun(seedPublishedManifest(key, actionCount, liveIdentity.xrspsBuildCommit()), key);
      ReplayRun terminal = awaitTerminal(created.runId(), Duration.ofSeconds(60));
      assertTrue(terminal.isTerminal(), "fixture worker run must become terminal: " + key);
      assertNotEquals(
          ReplayRunState.SUCCEEDED, terminal.getState(), "fixture cannot succeed: " + key);
      ReplayReport report = reportRepository.findByRunId(terminal.getId()).orElseThrow();
      assertNotEquals(
          ReplayOutcome.PASSED, report.getOutcome(), "fixture report cannot pass: " + key);
      assertEquals(terminal.getId(), report.getRunId());
      ReplayAttempt attempt =
          attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(terminal.getId()).orElseThrow();
      boolean leaseReleased =
          attempt.getLeaseExpiresAt() == null && !"RUNNING".equals(attempt.getState());
      boolean stagingReferencesEmpty =
          artifactRefRepository
              .findByRunIdAndState(terminal.getId(), ArtifactRef.STATE_STAGING)
              .isEmpty();
      int finalizedReferenceCount = finalizedReferenceCount(terminal.getId());
      boolean finalizedReferencesValid = finalizedReferencesValid(terminal.getId(), 1);
      assertTrue(leaseReleased, "fixture worker lease must be released: " + key);
      assertTrue(stagingReferencesEmpty, "fixture worker cannot leave staging refs: " + key);
      assertTrue(
          finalizedReferencesValid, "fixture worker finalized refs must be readable: " + key);
      return new DurableFailureObservation(
          terminal,
          report,
          leaseReleased,
          stagingReferencesEmpty,
          finalizedReferenceCount,
          finalizedReferencesValid);
    } finally {
      xrsps.setEntrypoint(originalEntrypoint);
      xrsps.setWorkingDirectory(originalWorkingDirectory);
    }
  }

  private int finalizedReferenceCount(UUID runId) {
    return artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_FINALIZED).size();
  }

  private boolean finalizedReferencesValid(UUID runId, int expectedCount) {
    List<ArtifactRef> finalized =
        artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_FINALIZED);
    if (finalized.size() != expectedCount) {
      return false;
    }
    for (ArtifactRef ref : finalized) {
      try {
        readAndVerifyFinalizedArtifact(ref);
      } catch (RuntimeException failure) {
        return false;
      }
    }
    return true;
  }

  private FailureObservation runFixture(Path script, String sequence) {
    ReplayProperties.Xrsps xrsps = xrspsConfig(script, 1500);
    XrspsKernelProcess kernel = new XrspsKernelProcess(xrsps, mapper);
    try {
      kernel.start();
      switch (sequence) {
        case "capabilities" -> sendOrFail(kernel, "capabilities", null);
        case "reset" -> {
          sendOrFail(kernel, "capabilities", null);
          sendOrFail(kernel, "reset", mapper.createObjectNode().putObject("manifest"));
        }
        case "snapshot" -> {
          sendOrFail(kernel, "capabilities", null);
          sendOrFail(kernel, "reset", mapper.createObjectNode().putObject("manifest"));
          sendOrFail(
              kernel,
              "step",
              mapper
                  .createObjectNode()
                  .put("expectedTick", 0)
                  .put("maxTicks", 1)
                  .putObject("action"));
          sendOrFail(kernel, "snapshot", null);
        }
        case "malformed", "timeout" -> sendOrFail(kernel, "ping", null);
        default -> throw new IllegalArgumentException("unknown fixture sequence " + sequence);
      }
      fail("fixture did not fail: " + script);
      return new FailureObservation("NO_FAILURE", "");
    } catch (FixtureFailure failure) {
      return new FailureObservation(failure.code, failure.getMessage());
    } catch (ReplayException failure) {
      return new FailureObservation(failure.getErrorCode().name(), failure.getMessage());
    } finally {
      kernel.close();
    }
  }

  private void sendOrFail(XrspsKernelProcess kernel, String method, JsonNode params) {
    KernelMessage response = kernel.send(method, params, 750);
    if (response.isError()) {
      String code =
          response.error() != null ? response.error().path("code").asText("UNKNOWN") : "UNKNOWN";
      throw new FixtureFailure(code, "fixture returned an error for " + method);
    }
  }

  private ObjectNode runArtifactFailure(String name, boolean corrupt) throws Exception {
    WorkerArtifactFailureObservation first = runArtifactFailureOnce(name + "-1", corrupt);
    WorkerArtifactFailureObservation second = runArtifactFailureOnce(name + "-2", corrupt);
    assertEquals(
        first.error(), second.error(), "artifact failure code must be deterministic: " + name);
    assertEquals(
        first.runState(),
        second.runState(),
        "artifact failure state must be deterministic: " + name);
    assertEquals(
        first.terminalReason(),
        second.terminalReason(),
        "artifact failure terminal reason must be deterministic: " + name);
    assertEquals(
        first.reportOutcome(),
        second.reportOutcome(),
        "artifact failure report outcome must be deterministic: " + name);
    assertFalse(first.falseSuccess() || second.falseSuccess(), name);
    assertTrue(first.processesReleased() && second.processesReleased(), name);
    assertTrue(first.leaseReleased() && second.leaseReleased(), name);
    assertTrue(first.stagingReferencesEmpty() && second.stagingReferencesEmpty(), name);
    assertEquals(0, first.finalizedReferenceCount(), name);
    assertEquals(0, second.finalizedReferenceCount(), name);
    assertTrue(first.finalizedReferencesValid() && second.finalizedReferencesValid(), name);
    assertNoActiveLeasesOrStaging(List.of(first.runId(), second.runId()));
    ObjectNode result = mapper.createObjectNode();
    result.put("case", name);
    result.put("error", first.error());
    result.put("secondError", second.error());
    result.put("deterministic", first.deterministicWith(second));
    result.put("falseSuccess", first.falseSuccess() || second.falseSuccess());
    result.put("processesReleased", first.processesReleased() && second.processesReleased());
    result.put("runState", first.runState());
    result.put("secondRunState", second.runState());
    result.put("terminalReason", first.terminalReason());
    result.put("secondTerminalReason", second.terminalReason());
    result.put("reportPresent", first.reportPresent());
    result.put("secondReportPresent", second.reportPresent());
    result.put("reportOutcome", first.reportOutcome());
    result.put("secondReportOutcome", second.reportOutcome());
    result.put("leaseReleased", first.leaseReleased() && second.leaseReleased());
    result.put(
        "stagingReferencesEmpty",
        first.stagingReferencesEmpty() && second.stagingReferencesEmpty());
    result.put(
        "finalizedReferencesValid",
        first.finalizedReferencesValid() && second.finalizedReferencesValid());
    result.put("finalizedReferenceCount", first.finalizedReferenceCount());
    result.put("secondFinalizedReferenceCount", second.finalizedReferenceCount());
    result.put(
        "recoveryOrFailClosed", first.recoveryOrFailClosed() && second.recoveryOrFailClosed());
    return result;
  }

  /** Runs missing/corrupt staged bytes through the real worker and production finalization path. */
  private WorkerArtifactFailureObservation runArtifactFailureOnce(String key, boolean corrupt)
      throws Exception {
    Path workerLauncher = writeSyntheticWorkerLauncher(key);
    ReplayProperties.Xrsps xrsps = properties.getXrsps();
    String originalEntrypoint = xrsps.getEntrypoint();
    String originalWorkingDirectory = xrsps.getWorkingDirectory();
    xrsps.setEntrypoint(workerLauncher.toAbsolutePath().toString());
    xrsps.setWorkingDirectory(WORKER_WORKING_DIRECTORY);
    String processToken = workerLauncher.getFileName().toString();
    long processCountBefore = countProcessesContaining(processToken);
    FaultingArtifactService faultingService =
        new FaultingArtifactService(artifactRefRepository, properties, corrupt);
    ReplayWorker faultingWorker = null;
    try {
      faultingWorker = newWorker(faultingService);
      UUID runId =
          createRun(seedPublishedManifest(key, 1, liveIdentity.xrspsBuildCommit()), key).runId();
      ReplayRun terminal = awaitTerminal(faultingWorker, runId, Duration.ofSeconds(90));
      ReplayReport report = reportRepository.findByRunId(runId).orElseThrow();
      ReplayAttempt latestAttempt =
          attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId).orElseThrow();
      boolean leaseReleased =
          latestAttempt.getLeaseExpiresAt() == null && !"RUNNING".equals(latestAttempt.getState());
      boolean stagingReferencesEmpty =
          artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING).isEmpty();
      int finalizedReferenceCount = finalizedReferenceCount(runId);
      boolean finalizedReferencesValid = finalizedReferencesValid(runId, 0);
      boolean processesReleased;
      try {
        waitForNoAdditionalProcesses(processCountBefore, processToken);
        processesReleased = true;
      } catch (AssertionError leak) {
        processesReleased = false;
        throw leak;
      }
      assertTrue(terminal.isTerminal(), "artifact fault must terminalize the run: " + key);
      assertNotEquals(ReplayRunState.SUCCEEDED, terminal.getState(), key);
      assertNotEquals(ReplayOutcome.PASSED, report.getOutcome(), key);
      assertEquals(
          corrupt ? "REPLAY_ERROR:CHECKSUM_MISMATCH" : "INFRASTRUCTURE_FAILURE",
          terminal.getTerminalReason(),
          key);
      assertTrue(leaseReleased, "artifact fault must release its lease: " + key);
      assertTrue(stagingReferencesEmpty, "artifact fault must tombstone STAGING refs: " + key);
      assertTrue(finalizedReferencesValid, "finalized references must remain readable: " + key);
      return new WorkerArtifactFailureObservation(
          runId,
          failureCodeForArtifactFault(corrupt),
          terminal.getState().name(),
          terminal.getTerminalReason(),
          report.getOutcome().name(),
          reportRepository.findByRunId(runId).isPresent(),
          leaseReleased,
          stagingReferencesEmpty,
          finalizedReferenceCount,
          finalizedReferencesValid,
          processesReleased);
    } finally {
      if (faultingWorker != null) {
        faultingWorker.close();
        assertTrue(faultingWorker.isShutdownClean(), "faulting worker executor must terminate");
      }
      xrsps.setEntrypoint(originalEntrypoint);
      xrsps.setWorkingDirectory(originalWorkingDirectory);
    }
  }

  private String failureCodeForArtifactFault(boolean corrupt) {
    return corrupt
        ? ReplayErrorCode.CHECKSUM_MISMATCH.name()
        : ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED.name();
  }

  private ObjectNode runRealSnapshotFailure(String name, boolean runtimeMismatch) throws Exception {
    SnapshotFailureObservation first = runRealSnapshotFailureOnce(name + "-1", runtimeMismatch);
    SnapshotFailureObservation second = runRealSnapshotFailureOnce(name + "-2", runtimeMismatch);
    assertEquals(
        first.error(), second.error(), "real snapshot error must be deterministic: " + name);
    assertEquals(
        first.runState(), second.runState(), "real snapshot state must be deterministic: " + name);
    assertEquals(
        first.terminalReason(),
        second.terminalReason(),
        "real snapshot terminal reason must be deterministic: " + name);
    assertEquals(
        first.reportOutcome(),
        second.reportOutcome(),
        "real snapshot report outcome must be deterministic: " + name);
    assertEquals(
        first.parentSucceeded(),
        second.parentSucceeded(),
        "real snapshot parent setup must succeed on repeat: " + name);
    assertEquals("REPLAY_ERROR:" + first.error(), first.terminalReason(), name);
    assertEquals(ReplayRunState.FAILED.name(), first.runState(), name);
    assertEquals(ReplayOutcome.FAILED.name(), first.reportOutcome(), name);
    assertTrue(first.reportPresent(), "real snapshot failure must leave a durable report: " + name);
    assertTrue(second.reportPresent(), "real snapshot repeat must leave a durable report: " + name);
    assertTrue(first.leaseReleased(), "real snapshot lease must be released: " + name);
    assertTrue(second.leaseReleased(), "real snapshot repeat lease must be released: " + name);
    assertTrue(
        first.stagingReferencesEmpty() && second.stagingReferencesEmpty(),
        "real snapshot failure cannot leave STAGING references: " + name);
    assertTrue(
        first.finalizedReferencesValid() && second.finalizedReferencesValid(),
        "real snapshot failure finalized references must be readable: " + name);
    assertEquals(1, first.finalizedReferenceCount(), name);
    assertEquals(1, second.finalizedReferenceCount(), name);
    assertTrue(first.processesReleased() && second.processesReleased(), "process leak: " + name);
    assertNoActiveLeasesOrStaging(
        List.of(
            first.parentRunId(), first.childRunId(), second.parentRunId(), second.childRunId()));
    ObjectNode result = mapper.createObjectNode();
    result.put("case", name);
    result.put("error", first.error());
    result.put("secondError", second.error());
    result.put("deterministic", first.deterministicWith(second));
    result.put("falseSuccess", first.falseSuccess() || second.falseSuccess());
    result.put("processesReleased", first.processesReleased() && second.processesReleased());
    result.put("runState", first.runState());
    result.put("secondRunState", second.runState());
    result.put("terminalReason", first.terminalReason());
    result.put("secondTerminalReason", second.terminalReason());
    result.put("reportPresent", first.reportPresent());
    result.put("secondReportPresent", second.reportPresent());
    result.put("reportOutcome", first.reportOutcome());
    result.put("secondReportOutcome", second.reportOutcome());
    result.put("leaseReleased", first.leaseReleased() && second.leaseReleased());
    result.put(
        "stagingReferencesEmpty",
        first.stagingReferencesEmpty() && second.stagingReferencesEmpty());
    result.put(
        "finalizedReferencesValid",
        first.finalizedReferencesValid() && second.finalizedReferencesValid());
    result.put("finalizedReferenceCount", first.finalizedReferenceCount());
    result.put("secondFinalizedReferenceCount", second.finalizedReferenceCount());
    result.put(
        "recoveryOrFailClosed",
        first.runState().equals(ReplayRunState.FAILED.name())
            && second.runState().equals(ReplayRunState.FAILED.name())
            && first.reportOutcome().equals(ReplayOutcome.FAILED.name())
            && second.reportOutcome().equals(ReplayOutcome.FAILED.name()));
    result.put("parentReportsPresent", first.parentSucceeded() && second.parentSucceeded());
    result.put("snapshotMutation", runtimeMismatch ? "runtime-identity" : "internal-checksum");
    return result;
  }

  /**
   * Builds a real successful parent, mutates only its durable checkpoint input, and runs a real
   * branch through the compiled worker. The branch must fail during Java-side snapshot validation,
   * then still produce a durable failure report and release its lease.
   */
  private SnapshotFailureObservation runRealSnapshotFailureOnce(String key, boolean runtimeMismatch)
      throws Exception {
    long processCountBefore = countProcessesContaining("replay-kernel");
    UUID parentRunId =
        createRun(
                seedPublishedManifest(key + "-parent", 1, liveIdentity.xrspsBuildCommit()),
                key + "-parent")
            .runId();
    ReplayRun parent = awaitTerminal(parentRunId, Duration.ofSeconds(90));
    assertEquals(ReplayRunState.SUCCEEDED, parent.getState(), "snapshot parent must succeed");
    assertEquals(ReplayOutcome.PASSED, parent.getOutcome(), "snapshot parent must pass");
    assertTrue(
        reportRepository.findByRunId(parentRunId).isPresent(), "snapshot parent report missing");

    ReplayCheckpoint checkpoint =
        checkpointRepository
            .findByRunIdAndBranchKeyOrderByTickAsc(parentRunId, ReplayRunService.MAIN_BRANCH)
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("successful snapshot parent has no checkpoint"));
    String originalArtifactChecksum = checkpoint.getSnapshotArtifactChecksum();
    assertNotNull(originalArtifactChecksum, "checkpoint artifact checksum must be persisted");
    ArtifactRef originalRef =
        artifactRefRepository
            .findByRunIdAndKindAndChecksum(
                parentRunId, ArtifactKind.SNAPSHOT, originalArtifactChecksum)
            .filter(ref -> ArtifactRef.STATE_FINALIZED.equals(ref.getState()))
            .orElseThrow(() -> new AssertionError("checkpoint snapshot is not finalized"));
    byte[] originalBytes = artifactService.readFinalized(originalRef);
    assertEquals(originalRef.getChecksum(), projection.sha256Hex(originalBytes));

    if (runtimeMismatch) {
      injectRuntimeMismatchedSnapshot(parentRunId, checkpoint, originalBytes);
    } else {
      tx().executeWithoutResult(
              status -> {
                ReplayCheckpoint current =
                    checkpointRepository.findById(checkpoint.getId()).orElseThrow();
                current.setSnapshotChecksum("sha256:" + "0".repeat(64));
                checkpointRepository.saveAndFlush(current);
              });
    }

    ReplayRunResponse child =
        runService.branch(
            parentRunId,
            new BranchReplayRunRequest(
                checkpoint.getId().toString(), "stress-" + key, "snapshot-" + key),
            "stress-owner");
    createdRunIds.add(child.runId());
    ReplayRun terminal = awaitTerminal(child.runId(), Duration.ofSeconds(90));
    assertTrue(
        terminal.getTerminalReason() != null
            && terminal.getTerminalReason().startsWith("REPLAY_ERROR:"),
        "snapshot corruption must be classified as a replay contract error: " + terminal);
    ReplayReport report = reportRepository.findByRunId(child.runId()).orElseThrow();
    ReplayAttempt attempt =
        attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(child.runId()).orElseThrow();
    boolean leaseReleased =
        attempt.getLeaseExpiresAt() == null && !"RUNNING".equals(attempt.getState());
    boolean stagingReferencesEmpty =
        artifactRefRepository
            .findByRunIdAndState(child.runId(), ArtifactRef.STATE_STAGING)
            .isEmpty();
    int finalizedReferenceCount = finalizedReferenceCount(child.runId());
    boolean finalizedReferencesValid = finalizedReferencesValid(child.runId(), 1);
    waitForNoAdditionalProcesses(processCountBefore, "replay-kernel");
    assertNoFilesystemStaging();
    return new SnapshotFailureObservation(
        parentRunId,
        child.runId(),
        terminal.getTerminalReason().substring("REPLAY_ERROR:".length()),
        terminal.getState().name(),
        terminal.getTerminalReason(),
        report.getOutcome().name(),
        report.getRunId().equals(child.runId()),
        leaseReleased,
        stagingReferencesEmpty,
        finalizedReferenceCount,
        finalizedReferencesValid,
        !hasProcessContaining("replay-kernel"),
        parent.getState() == ReplayRunState.SUCCEEDED);
  }

  /**
   * Publishes a valid-but-runtime-incompatible snapshot through the real content-addressed store.
   */
  private void injectRuntimeMismatchedSnapshot(
      UUID parentRunId, ReplayCheckpoint checkpoint, byte[] originalBytes) throws Exception {
    ObjectNode snapshot = (ObjectNode) mapper.readTree(originalBytes);
    JsonNode runtimeNode = snapshot.path("runtimeIdentity");
    assertTrue(runtimeNode.isObject(), "certified snapshots must carry runtime identity");
    ObjectNode runtime = (ObjectNode) runtimeNode;
    runtime.put("xrspsBuildCommit", "0".repeat(40));
    snapshot.remove("checksum");
    String internalChecksum = projection.sha256Hex(projection.canonicalJsonBytes(snapshot));
    snapshot.put("checksum", internalChecksum);
    byte[] mutatedBytes = projection.canonicalJsonBytes(snapshot);
    ArtifactRef injected = publishInjectedSnapshot(parentRunId, mutatedBytes);
    tx().executeWithoutResult(
            status -> {
              ReplayCheckpoint current =
                  checkpointRepository.findById(checkpoint.getId()).orElseThrow();
              current.setSnapshotChecksum(internalChecksum);
              current.setSnapshotArtifactChecksum(injected.getChecksum());
              checkpointRepository.saveAndFlush(current);
            });
  }

  /** Stages, publishes, and then explicitly finalizes a test-only injected checkpoint artifact. */
  private ArtifactRef publishInjectedSnapshot(UUID runId, byte[] bytes) {
    PreparedArtifact prepared =
        artifactService.prepareArtifact(runId, ArtifactKind.SNAPSHOT, bytes, "application/json");
    ArtifactRef ref =
        new ArtifactRef(
            prepared.proposedReferenceId(),
            runId,
            ArtifactKind.SNAPSHOT,
            prepared.checksum(),
            bytes.length);
    ref.setMediaType("application/json");
    ref.setCreatedAt(databaseInstant());
    tx().executeWithoutResult(status -> artifactRefRepository.saveAndFlush(ref));
    try {
      artifactService.publishArtifacts(runId);
      tx().executeWithoutResult(
              status -> {
                ArtifactRef current = artifactRefRepository.findById(ref.getId()).orElseThrow();
                current.setState(ArtifactRef.STATE_FINALIZED);
                current.setFinalizedAt(databaseInstant());
                artifactRefRepository.saveAndFlush(current);
              });
    } catch (RuntimeException failure) {
      artifactService.discardPrepared(prepared);
      throw failure;
    }
    return artifactRefRepository.findById(ref.getId()).orElseThrow();
  }

  private ObjectNode runManifestPinFailure() throws Exception {
    ObjectNode first = runManifestPinFailureOnce("manifest-pin-failure-1");
    ObjectNode second = runManifestPinFailureOnce("manifest-pin-failure-2");
    assertEquals(first.path("error").asText(), second.path("error").asText());
    assertEquals(first.path("runState").asText(), second.path("runState").asText());
    assertEquals(first.path("terminalReason").asText(), second.path("terminalReason").asText());
    assertEquals(first.path("reportOutcome").asText(), second.path("reportOutcome").asText());
    first.put("secondError", second.path("error").asText());
    first.put("secondRunState", second.path("runState").asText());
    first.put("secondTerminalReason", second.path("terminalReason").asText());
    first.put("secondReportOutcome", second.path("reportOutcome").asText());
    first.put("deterministic", true);
    first.put(
        "processesReleased",
        first.path("processesReleased").asBoolean()
            && second.path("processesReleased").asBoolean());
    first.put(
        "leaseReleased",
        first.path("leaseReleased").asBoolean() && second.path("leaseReleased").asBoolean());
    first.put(
        "stagingReferencesEmpty",
        first.path("stagingReferencesEmpty").asBoolean()
            && second.path("stagingReferencesEmpty").asBoolean());
    first.put(
        "finalizedReferencesValid",
        first.path("finalizedReferencesValid").asBoolean()
            && second.path("finalizedReferencesValid").asBoolean());
    first.put(
        "recoveryOrFailClosed",
        first.path("recoveryOrFailClosed").asBoolean()
            && second.path("recoveryOrFailClosed").asBoolean());
    first.put("secondReportPresent", second.path("reportPresent").asBoolean());
    return first;
  }

  private ObjectNode runManifestPinFailureOnce(String key) throws Exception {
    long processCountBefore = countProcessesContaining("replay-kernel");
    UUID badManifest = seedPublishedManifest(key, 2, "f".repeat(40));
    ReplayRunResponse created = createRun(badManifest, key);
    ReplayRun terminal = awaitTerminal(created.runId(), Duration.ofSeconds(60));
    assertEquals(ReplayRunState.FAILED, terminal.getState());
    assertEquals("REPLAY_ERROR:XRSPS_VERSION_INCOMPATIBLE", terminal.getTerminalReason());
    ReplayReport report = reportRepository.findByRunId(terminal.getId()).orElseThrow();
    assertEquals(ReplayOutcome.FAILED, report.getOutcome());
    assertEquals(liveIdentity.xrspsBuildCommit(), report.getXrspsBuildCommit());
    ReplayAttempt attempt =
        attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(terminal.getId()).orElseThrow();
    boolean leaseReleased =
        attempt.getLeaseExpiresAt() == null && !"RUNNING".equals(attempt.getState());
    assertTrue(leaseReleased);
    assertTrue(
        artifactRefRepository
            .findByRunIdAndState(terminal.getId(), ArtifactRef.STATE_STAGING)
            .isEmpty());
    int finalizedReferenceCount = finalizedReferenceCount(terminal.getId());
    assertTrue(finalizedReferencesValid(terminal.getId(), 1));
    waitForNoAdditionalProcesses(processCountBefore, "replay-kernel");
    assertNoActiveLeasesOrStaging(List.of(terminal.getId()));
    ObjectNode result = mapper.createObjectNode();
    result.put("case", "manifest-runtime-pin-mismatch");
    result.put("runState", terminal.getState().name());
    result.put("terminalReason", terminal.getTerminalReason());
    result.put("error", terminal.getTerminalReason());
    result.put("deterministic", true);
    result.put(
        "falseSuccess", terminal.isTerminal() && terminal.getState() == ReplayRunState.SUCCEEDED);
    result.put(
        "processesReleased", countProcessesContaining("replay-kernel") <= processCountBefore);
    result.put("reportPresent", true);
    result.put("reportOutcome", report.getOutcome().name());
    result.put("leaseReleased", leaseReleased);
    result.put("stagingReferencesEmpty", true);
    result.put("finalizedReferenceCount", finalizedReferenceCount);
    result.put("finalizedReferencesValid", finalizedReferencesValid(terminal.getId(), 1));
    result.put("recoveryOrFailClosed", terminal.getState() == ReplayRunState.FAILED);
    return result;
  }

  private ObjectNode runFinalizationUniquenessConflict() throws Exception {
    ObjectNode first = runFinalizationUniquenessConflictOnce("finalization-unique-conflict-1");
    ObjectNode second = runFinalizationUniquenessConflictOnce("finalization-unique-conflict-2");
    assertEquals(first.path("error").asText(), second.path("error").asText());
    assertEquals(first.path("reportOutcome").asText(), second.path("reportOutcome").asText());
    assertEquals(first.path("runState").asText(), second.path("runState").asText());
    first.put("secondError", second.path("error").asText());
    first.put("secondReportOutcome", second.path("reportOutcome").asText());
    first.put("secondRunState", second.path("runState").asText());
    first.put(
        "deterministic",
        first.path("deterministic").asBoolean() && second.path("deterministic").asBoolean());
    first.put(
        "falseSuccess",
        first.path("falseSuccess").asBoolean() || second.path("falseSuccess").asBoolean());
    first.put("secondReports", second.path("reports").asLong());
    first.put("secondUniqueConflictObserved", second.path("uniqueConflictObserved").asBoolean());
    return first;
  }

  private ObjectNode runFinalizationUniquenessConflictOnce(String key) throws Exception {
    UUID runId = seedTerminalRunWithoutReport();
    List<String> outcomes = runTwoRacers(key, side -> reportWriter.repair(runId).name());
    assertExactRaceOutcomes(
        outcomes,
        Set.of("INSERTED", "CONCURRENT_WINNER_ACCEPTED", "ALREADY_PRESENT", "LOCK_CONTENDED"),
        key);
    assertEquals(1, outcomes.stream().filter("INSERTED"::equals).count());
    long reports = tx().execute(status -> reportRepository.findByRunId(runId).stream().count());
    UniquenessConflictObservation uniqueConflict = provokeReportUniquenessConflict(runId);
    ReplayReport report = reportRepository.findByRunId(runId).orElseThrow();
    assertEquals(ReplayOutcome.INFRASTRUCTURE_FAILURE, report.getOutcome());
    assertNoFilesystemStaging();
    ObjectNode result = mapper.createObjectNode();
    result.put("case", "postgres-uniqueness-conflict-during-finalization");
    result.put("error", "REPORT_UNIQUE_CONSTRAINT");
    result.put("deterministic", reports == 1 && uniqueConflict.observed());
    result.put("falseSuccess", reports != 1);
    result.put("processesReleased", true);
    result.put("reports", reports);
    result.put("uniqueConflictObserved", uniqueConflict.observed());
    result.put("uniqueConflictSqlState", uniqueConflict.sqlState());
    result.put("uniqueConflictConstraint", uniqueConflict.constraint());
    result.put("winnerReportStillPresent", reportRepository.findByRunId(runId).isPresent());
    result.put("runState", runRepository.findById(runId).orElseThrow().getState().name());
    result.put("reportPresent", true);
    result.put("reportOutcome", report.getOutcome().name());
    result.put("leaseReleased", true);
    result.put(
        "stagingReferencesEmpty",
        artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING).isEmpty());
    result.put("finalizedReferenceCount", finalizedReferenceCount(runId));
    result.put("finalizedReferencesValid", finalizedReferencesValid(runId, 0));
    result.put("recoveryOrFailClosed", uniqueConflict.observed() && reports == 1);
    result.set("racers", mapper.valueToTree(outcomes));
    return result;
  }

  /**
   * Replays the database uniqueness failure at the final report boundary after the winner is
   * committed. The failed insert is isolated in its own transaction; the committed winner must
   * remain the only report and remain readable afterwards.
   */
  private UniquenessConflictObservation provokeReportUniquenessConflict(UUID runId) {
    try {
      tx().executeWithoutResult(
              status ->
                  entityManager
                      .createNativeQuery(
                          "INSERT INTO replay_reports (run_id, attempt_id, mode, manifest_checksum, outcome, "
                              + "executed_action_count, checkpoint_count, mismatch_hard_count, "
                              + "mismatch_soft_count, mismatch_info_count, final_state_hash, final_chain_hash, "
                              + "terminal_reason, xrsps_adapter_version, xrsps_build_commit, cache_revision, "
                              + "content_revision, report_json, report_checksum, control_allowed, started_at, "
                              + "terminal_at, finalized_at) "
                              + "SELECT run_id, attempt_id, mode, manifest_checksum, outcome, executed_action_count, "
                              + "checkpoint_count, mismatch_hard_count, mismatch_soft_count, mismatch_info_count, "
                              + "final_state_hash, final_chain_hash, terminal_reason, xrsps_adapter_version, "
                              + "xrsps_build_commit, cache_revision, content_revision, report_json, report_checksum, "
                              + "control_allowed, started_at, terminal_at, finalized_at "
                              + "FROM replay_reports WHERE run_id = :runId")
                      .setParameter("runId", runId)
                      .executeUpdate());
      fail("the duplicate replay report insert must violate replay_reports.run_id uniqueness");
      return new UniquenessConflictObservation(false, "NO_CONFLICT", "");
    } catch (RuntimeException expected) {
      SQLException sql = findSqlException(expected);
      assertEquals("23505", sql.getSQLState(), "finalization conflict must be unique_violation");
      String messages = allMessages(expected).toLowerCase(Locale.ROOT);
      assertTrue(
          messages.contains("replay_reports_run_id_key")
              || messages.contains("replay_reports.run_id")
              || messages.contains("run_id"),
          "the conflict must be the replay report run_id uniqueness constraint: " + messages);
      String constraint =
          messages.contains("replay_reports_run_id_key") ? "replay_reports_run_id_key" : "run_id";
      return new UniquenessConflictObservation(true, sql.getSQLState(), constraint);
    }
  }

  // ── PostgreSQL race batteries ──

  private ObjectNode runClaimRaces() throws Exception {
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      final int runNumber = repetition;
      UUID runId =
          createRun(
                  seedPublishedManifest("claim-" + runNumber, 0, liveIdentity.xrspsBuildCommit()),
                  "claim-" + runNumber)
              .runId();
      List<String> racers =
          runTwoRacers(
              "claim-race-" + runNumber,
              side -> {
                ReplayRunService.ClaimResult claim =
                    runService.claimAtomically(runId, "claim-racer-" + side + "-" + runNumber);
                return claim == null ? "LOSER" : "WINNER";
              });
      assertExactRaceOutcomes(racers, Set.of("WINNER", "LOSER"), "claim-race-" + runNumber);
      assertEquals(
          1,
          racers.stream().filter("WINNER"::equals).count(),
          () -> "claim race must have exactly one winner: " + racers);
      assertEquals(1, attemptRepository.findByRunIdOrderByAttemptNoAsc(runId).size());
      recoverClaimedRun(runId);
      observations.add(racers);
    }
    return raceSummary("two-workers-claim-one-queued-run", observations);
  }

  private ObjectNode runExpiredAttemptRecoveryRaces() throws Exception {
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      final int runNumber = repetition;
      UUID[] ids = seedExpiredLeaseAttempt("recovery-" + runNumber);
      List<String> racers =
          runTwoRacers(
              "recovery-race-" + runNumber,
              side -> recoveryService.recover(ids[1]).map(Enum::name).orElse("NONE"));
      assertExactRaceOutcomes(racers, Set.of("FAILED", "NONE"), "recovery-race-" + runNumber);
      assertEquals(1, racers.stream().filter("FAILED"::equals).count());
      ReplayRun terminal = runRepository.findById(ids[0]).orElseThrow();
      assertTrue(terminal.isTerminal());
      assertTrue(reportRepository.findByRunId(ids[0]).isPresent());
      observations.add(racers);
    }
    return raceSummary("two-workers-reclaim-one-expired-attempt", observations);
  }

  private ObjectNode runManifestChecksumRaces() throws Exception {
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      final int runNumber = repetition;
      UUID curationId = seedCuration("compiler-race-" + runNumber);
      String checksum = String.format(Locale.ROOT, "sha256:%064x", runNumber);
      List<String> racers =
          runTwoRacers(
              "manifest-race-" + runNumber,
              side -> {
                ScenarioManifest manifest = manifestEntity(curationId, checksum, runNumber);
                try {
                  manifestInsertService.insert(manifest);
                  return "WINNER";
                } catch (DataIntegrityViolationException
                    | org.springframework.transaction.UnexpectedRollbackException expected) {
                  SQLException sql = findSqlException(expected);
                  assertEquals(
                      "23505",
                      sql.getSQLState(),
                      "manifest checksum race loser must be unique_violation");
                  String message = allMessages(expected).toLowerCase(Locale.ROOT);
                  assertTrue(
                      message.contains("manifest_checksum")
                          || message.contains("scenario_manifests_manifest_checksum_key"),
                      "manifest checksum race hit the wrong constraint: " + message);
                  return "LOSER:23505:manifest_checksum";
                }
              });
      assertExactRaceOutcomes(
          racers, Set.of("WINNER", "LOSER:23505:manifest_checksum"), "manifest-race-" + runNumber);
      assertEquals(
          1,
          racers.stream().filter("WINNER"::equals).count(),
          () -> "manifest checksum race must have exactly one winner: " + racers);
      assertEquals(1, manifestRepository.findByManifestChecksum(checksum).stream().count());
      observations.add(racers);
    }
    return raceSummary("two-compilers-one-manifest-checksum", observations);
  }

  private ObjectNode runArtifactPublisherRaces() throws Exception {
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      final int runNumber = repetition;
      byte[] bytes = ("publisher-race-" + runNumber).getBytes(StandardCharsets.UTF_8);
      PublisherFixture a = preparePublisherFixture("publisher-a-" + runNumber, bytes);
      PublisherFixture b = preparePublisherFixture("publisher-b-" + runNumber, bytes);
      List<String> racers =
          runTwoRacers(
              "publisher-race-" + runNumber, side -> finalizePublisherFixture(side == 0 ? a : b));
      assertExactRaceOutcomes(
          racers,
          Set.of("FINALIZED:CREATED", "FINALIZED:REUSED_EXISTING"),
          "publisher-race-" + runNumber);
      assertEquals(1, racers.stream().filter("FINALIZED:CREATED"::equals).count());
      assertEquals(1, racers.stream().filter("FINALIZED:REUSED_EXISTING"::equals).count());
      UUID runA = a.runId();
      UUID runB = b.runId();
      String hex = a.traceChecksum().substring("sha256:".length());
      Path finalPath = artifactRoot.resolve(hex.substring(0, 2)).resolve(hex);
      assertTrue(Files.isRegularFile(finalPath));
      assertEquals(a.traceChecksum(), b.traceChecksum());
      byte[] readback = Files.readAllBytes(finalPath);
      assertEquals(bytes.length, readback.length);
      assertEquals(a.traceChecksum(), projection.sha256Hex(readback));
      try (var finalEntries = Files.list(finalPath.getParent())) {
        assertEquals(
            1L,
            finalEntries
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals(hex))
                .count(),
            "one shared final content-addressed file must exist for " + a.traceChecksum());
      }
      assertTrue(
          artifactRefRepository.findByRunIdAndState(runA, ArtifactRef.STATE_FINALIZED).stream()
                  .count()
              == 2,
          "publisher A must finalize its trace and report through the production fence");
      assertTrue(
          artifactRefRepository.findByRunIdAndState(runB, ArtifactRef.STATE_FINALIZED).stream()
                  .count()
              == 2,
          "publisher B must finalize its trace and report through the production fence");
      assertTrue(
          artifactRefRepository.findByRunIdAndState(runA, ArtifactRef.STATE_STAGING).isEmpty());
      assertTrue(
          artifactRefRepository.findByRunIdAndState(runB, ArtifactRef.STATE_STAGING).isEmpty());
      assertEquals(ReplayRunState.SUCCEEDED, runRepository.findById(runA).orElseThrow().getState());
      assertEquals(ReplayRunState.SUCCEEDED, runRepository.findById(runB).orElseThrow().getState());
      int finalizedReferenceCountA = finalizedReferenceCount(runA);
      int finalizedReferenceCountB = finalizedReferenceCount(runB);
      assertEquals(2, finalizedReferenceCountA);
      assertEquals(2, finalizedReferenceCountB);
      assertTrue(finalizedReferencesValid(runA, 2));
      assertTrue(finalizedReferencesValid(runB, 2));
      assertNoActiveLeasesOrStaging(List.of(runA, runB));
      observations.add(racers);
    }
    return raceSummary("two-publishers-one-content-address", observations);
  }

  /**
   * Creates a fully fenced RUNNING/FINALIZE_ARTIFACTS fixture without bypassing production doors.
   */
  private PublisherFixture preparePublisherFixture(String key, byte[] traceBytes) {
    UUID runId =
        createRun(seedPublishedManifest(key, 0, liveIdentity.xrspsBuildCommit()), key).runId();
    ReplayRunService.ClaimResult claim =
        tx().execute(status -> runService.claimAtomically(runId, "publisher-" + key));
    assertNotNull(claim);
    ReplayFence fence = claim.fence();
    persistence.transitionStarting(fence);
    persistence.transitionRunning(fence, ReplayRunPhase.COMPARE);
    persistence.storeRuntimeIdentity(fence, liveIdentity);

    PreparedArtifact trace =
        artifactService.prepareArtifact(
            runId, ArtifactKind.TRACE, traceBytes, "application/octet-stream");
    persistence.registerArtifact(fence, trace);
    ReplayWorkerPersistenceService.FinalizationReservation reservation =
        persistence.reserveSuccessfulFinalization(fence, ReplayOutcome.PASSED);
    ReplayRun run = runRepository.findById(runId).orElseThrow();
    ReplayAttempt attempt = attemptRepository.findById(claim.attempt().getId()).orElseThrow();
    ReplayReportService.PreparedReplayReport prepared =
        reportService.prepareReport(
            run,
            attempt,
            ReplayOutcome.PASSED,
            null,
            0,
            0,
            null,
            null,
            List.of(trace.checksum()),
            reservation.reservedAt(),
            null);
    PreparedArtifact report =
        artifactService.prepareArtifact(
            runId, ArtifactKind.REPORT, prepared.canonicalBytes(), "application/json");
    persistence.registerArtifact(fence, report);
    return new PublisherFixture(
        runId,
        fence,
        reservation,
        reportService.materialize(prepared, run, attempt),
        trace.checksum());
  }

  private String finalizePublisherFixture(PublisherFixture fixture) {
    ArtifactPublicationReceipt receipt = artifactService.publishArtifacts(fixture.runId());
    String traceDisposition =
        receipt.published().stream()
            .filter(published -> published.kind() == ArtifactKind.TRACE)
            .map(PublishedArtifact::disposition)
            .map(Enum::name)
            .findFirst()
            .orElseThrow(() -> new AssertionError("publisher receipt omitted TRACE"));
    persistence.finalizeSuccessfulRun(
        fixture.fence(), fixture.reservation(), receipt, fixture.report(), ReplayOutcome.PASSED);
    return "FINALIZED:" + traceDisposition;
  }

  private ObjectNode runRecoveryVersusNormalCompletionRaces() throws Exception {
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      final int runNumber = repetition;
      UUID[] ids = seedExpiringRaceRun("completion-race-" + runNumber);
      ReplayAttempt attempt = attemptRepository.findById(ids[1]).orElseThrow();
      ReplayFence fence =
          new ReplayFence(
              ids[0],
              ids[1],
              attempt.getAttemptNo(),
              attempt.getFencingGeneration(),
              attempt.getWorkerIdentity());
      CyclicBarrier completionStarted = new CyclicBarrier(2);
      List<String> racers =
          runTwoRacers(
              "completion-race-" + runNumber,
              side -> {
                awaitBarrier(completionStarted);
                return side == 0 ? normalCompletion(fence) : recoveryOutcome(ids[1]);
              });
      assertExactRaceOutcomes(
          racers,
          Set.of(
              "NORMAL_WINNER",
              "NORMAL_NO_DECISION",
              "NORMAL_LOSER:ATTEMPT_STALE_FENCE",
              "NORMAL_LOSER:RUN_IMMUTABLE",
              "RECOVERY_FAILED",
              "RECOVERY_NONE"),
          "completion-race-" + runNumber);
      long completionWinners =
          racers.stream()
              .filter(value -> value.equals("NORMAL_WINNER") || value.equals("RECOVERY_FAILED"))
              .count();
      assertEquals(
          1L,
          completionWinners,
          () ->
              "exactly one completion race winner must finalize: outcomes="
                  + racers
                  + ", run="
                  + ids[0]
                  + ", state="
                  + runRepository.findById(ids[0]).map(ReplayRun::getState).orElse(null)
                  + ", reportPresent="
                  + reportRepository.findByRunId(ids[0]).isPresent());
      ReplayRun terminal = runRepository.findById(ids[0]).orElseThrow();
      assertTrue(terminal.isTerminal());
      assertTrue(reportRepository.findByRunId(ids[0]).isPresent());
      assertFalse(terminal.getState() == ReplayRunState.QUEUED);
      observations.add(racers);
    }
    return raceSummary("recovery-versus-normal-completion", observations);
  }

  private ObjectNode runReportWriterRaces() throws Exception {
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      UUID runId = seedTerminalRunWithoutReport();
      List<String> racers =
          runTwoRacers(
              "report-writer-race-" + repetition, side -> reportWriter.repair(runId).name());
      assertExactRaceOutcomes(
          racers,
          Set.of("INSERTED", "CONCURRENT_WINNER_ACCEPTED", "ALREADY_PRESENT", "LOCK_CONTENDED"),
          "report-writer-race-" + repetition);
      assertEquals(
          1L,
          racers.stream().filter("INSERTED"::equals).count(),
          "exactly one report writer must insert");
      assertEquals(1, reportRepository.findByRunId(runId).stream().count());
      assertTrue(
          reportRepository.findByRunId(runId).map(ReplayReport::getOutcome).orElse(null)
              == ReplayOutcome.INFRASTRUCTURE_FAILURE);
      observations.add(racers);
    }
    return raceSummary("two-report-writers-finalize-one-run", observations);
  }

  private ObjectNode runCatalogContinuationRaces() throws Exception {
    ensureCatalogTables();
    List<List<String>> observations = new ArrayList<>();
    for (int repetition = 0; repetition < 25; repetition++) {
      final int runNumber = repetition;
      seedCatalogRows(runNumber);
      Object[] first =
          tx().execute(status -> catalogRepository.findCatalogRows(null, null, null, 1, 0).get(0));
      LocalDateTime cursorTime = asLocalDateTime(first[5]);
      Long cursorId = ((Number) first[0]).longValue();
      CyclicBarrier continuationStarted = new CyclicBarrier(2);
      List<String> racers =
          runTwoRacers(
              "catalog-continuation-" + runNumber,
              side -> {
                if (side == 0) {
                  List<Object[]> continuation =
                      tx().execute(
                              status -> {
                                // The barrier is reached after this transaction has started. The
                                // sleep keeps the same READ COMMITTED transaction open while the
                                // competing session is inserted, making the continuation race
                                // deterministic instead of relying on thread scheduling.
                                awaitBarrier(continuationStarted);
                                entityManager
                                    .createNativeQuery("SELECT pg_sleep(0.200)")
                                    .getSingleResult();
                                return catalogRepository.findCatalogRows(
                                    null, cursorTime, cursorId, 10, 0);
                              });
                  List<String> recordingIds =
                      continuation.stream().map(row -> String.valueOf(row[1])).toList();
                  assertEquals(
                      List.of("recording-older-" + runNumber),
                      recordingIds,
                      "keyset continuation must remain stable when a newer row is inserted");
                  return recordingIds.toString();
                }
                awaitBarrier(continuationStarted);
                tx().executeWithoutResult(
                        status ->
                            insertCatalogSession(
                                "newer-" + runNumber,
                                "recording-newer-" + runNumber,
                                LocalDateTime.of(2026, 8, 28, 5, 0)));
                return "INSERTED";
              });
      assertExactRaceOutcomes(
          racers,
          Set.of("[recording-older-" + runNumber + "]", "INSERTED"),
          "catalog-continuation-" + runNumber);
      assertEquals("[recording-older-" + runNumber + "]", racers.get(0));
      assertEquals("INSERTED", racers.get(1));
      observations.add(racers);
    }
    return raceSummary("catalog-continuation-during-newer-insert", observations);
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while coordinating stress race", interrupted);
    } catch (java.util.concurrent.BrokenBarrierException | TimeoutException coordinationFailure) {
      throw new IllegalStateException(
          "Stress race coordination barrier failed", coordinationFailure);
    }
  }

  private List<String> runTwoRacers(String name, IntFunction<String> action) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    List<Future<String>> futures = new ArrayList<>();
    try {
      for (int side = 0; side < 2; side++) {
        final int racer = side;
        futures.add(
            pool.submit(
                (Callable<String>)
                    () -> {
                      barrier.await(30, TimeUnit.SECONDS);
                      return action.apply(racer);
                    }));
      }
      List<String> results = new ArrayList<>();
      for (Future<String> future : futures) {
        try {
          results.add(future.get(60, TimeUnit.SECONDS));
        } catch (TimeoutException timeout) {
          throw new AssertionError(
              "Timed out in race " + name + ": " + databaseDiagnostics(), timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
          throw new AssertionError(
              "Racer failed in " + name + ": " + failure.getCause(), failure.getCause());
        }
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Race helpers must never turn a failed worker into a passing observation. Every caller gets
   * exactly two results, each result is explicitly classified by the scenario, and an exception
   * returned by a racer is rejected before the scenario can be serialized as passed.
   */
  private void assertExactRaceOutcomes(
      List<String> outcomes, Set<String> allowed, String raceName) {
    assertEquals(2, outcomes.size(), "two racers must report for " + raceName);
    assertTrue(
        outcomes.stream().allMatch(allowed::contains),
        () -> "unexpected outcome in " + raceName + ": " + outcomes);
    assertTrue(
        outcomes.stream().noneMatch(value -> value.startsWith("ERROR:")),
        () -> "racer exception was swallowed in " + raceName + ": " + outcomes);
  }

  /** Returns the PostgreSQL cause from a Spring/JPA exception, failing closed when absent. */
  private static SQLException findSqlException(Throwable error) {
    Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    Throwable current = error;
    while (current != null && visited.add(current)) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    throw new AssertionError("Expected a PostgreSQL SQLException: " + allMessages(error), error);
  }

  /** Includes the complete cause chain in evidence so a wrong constraint cannot look equivalent. */
  private static String allMessages(Throwable error) {
    StringBuilder messages = new StringBuilder();
    Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    Throwable current = error;
    while (current != null && visited.add(current)) {
      if (messages.length() > 0) {
        messages.append(" | ");
      }
      messages.append(current.getClass().getName()).append(": ").append(current.getMessage());
      current = current.getCause();
    }
    return messages.toString();
  }

  private ObjectNode raceSummary(String name, List<List<String>> observations) {
    ObjectNode summary = mapper.createObjectNode();
    summary.put("name", name);
    summary.put("repetitions", observations.size());
    summary.put(
        "passed",
        observations.size() == 25
            && observations.stream()
                .noneMatch(row -> row.stream().anyMatch(value -> value.startsWith("ERROR:"))));
    summary.set("observations", mapper.valueToTree(observations));
    return summary;
  }

  private String normalCompletion(ReplayFence fence) {
    try {
      ReplayWorkerPersistenceService.TerminalDecision decision =
          persistence.reserveFailureFinalization(
              fence, ReplayRunState.FAILED, ReplayOutcome.INFRASTRUCTURE_FAILURE, "RACE_INJECTED");
      if (decision == null) {
        return "NORMAL_NO_DECISION";
      }
      ReplayRun run = runRepository.findById(fence.runId()).orElseThrow();
      ReplayAttempt attempt = attemptRepository.findById(fence.attemptId()).orElseThrow();
      ReplayReportService.PreparedReplayReport prepared =
          reportService.prepareReport(
              run,
              attempt,
              decision.outcome(),
              decision.reason(),
              0,
              0,
              null,
              null,
              List.of(),
              decision.terminalAt(),
              null);
      persistence.finalizeFailure(
          fence, decision, reportService.materialize(prepared, run, attempt), null);
      return "NORMAL_WINNER";
    } catch (ReplayException failure) {
      return "NORMAL_LOSER:" + failure.getErrorCode().name();
    }
  }

  private String recoveryOutcome(UUID attemptId) {
    return recoveryService
        .recover(attemptId)
        .map(state -> "RECOVERY_" + state.name())
        .orElse("RECOVERY_NONE");
  }

  // ── database fixtures ──

  private UUID seedPublishedManifest(String key, int actionCount, String buildCommit) {
    UUID curationId = UUID.randomUUID();
    UUID manifestId = UUID.randomUUID();
    String recordingId = "stress-recording-" + key + "-" + UUID.randomUUID();
    ObjectNode document = manifestDocument(manifestId, curationId, actionCount, buildCommit);
    String checksum = projection.sha256Hex(projection.canonicalJsonBytes(document));
    document.put("manifestChecksum", checksum);
    tx.executeWithoutResult(
        status -> {
          entityManager
              .createNativeQuery(
                  "INSERT INTO recording_curations (id, recording_id, debug_session_id, version, state, role, content_json, content_checksum, created_by)"
                      + " VALUES (:id, :recording, :session, 1, 'PUBLISHED', 'TRAIN', CAST(:json AS jsonb), :checksum, 'stress')")
              .setParameter("id", curationId)
              .setParameter("recording", recordingId)
              .setParameter("session", "stress-session-" + key)
              .setParameter("json", "{}")
              .setParameter("checksum", "sha256:" + "c".repeat(64))
              .executeUpdate();
          entityManager
              .createNativeQuery(
                  "INSERT INTO scenario_manifests (id, curation_id, recording_id, debug_session_id, recording_checksum, debug_session_checksum, compiler_version, compiler_schema_version, xrsps_adapter_version, xrsps_build_commit, cache_revision, content_revision, seed, fixed_clock_origin, start_tick, end_tick, manifest_json, manifest_checksum, completeness, publish_state, created_at)"
                      + " VALUES (:id, :curation, :recording, :session, :recordingChecksum, :debugChecksum, 'stress', 1, :adapter, :build, :cache, :content, 'stress-seed', TIMESTAMP '2026-01-01 00:00:00', 0, 500, CAST(:json AS jsonb), :checksum, 'COMPLETE', 'PUBLISHED', CURRENT_TIMESTAMP)")
              .setParameter("id", manifestId)
              .setParameter("curation", curationId)
              .setParameter("recording", recordingId)
              .setParameter("session", "stress-session-" + key)
              .setParameter("recordingChecksum", "sha256:" + "a".repeat(64))
              .setParameter("debugChecksum", "sha256:" + "b".repeat(64))
              .setParameter("adapter", liveIdentity.xrspsAdapterVersion())
              .setParameter("build", buildCommit)
              .setParameter("cache", liveIdentity.cacheRevision())
              .setParameter("content", liveIdentity.contentRevision())
              .setParameter("json", document.toString())
              .setParameter("checksum", checksum)
              .executeUpdate();
        });
    return manifestId;
  }

  private ObjectNode manifestDocument(
      UUID manifestId, UUID curationId, int actionCount, String buildCommit) {
    ObjectNode document = mapper.createObjectNode();
    document.put("manifestVersion", 1);
    document.put("manifestId", manifestId.toString());
    ObjectNode recording = document.putObject("recording");
    recording.put("recordingId", "stress-recording");
    recording.put("recordingChecksum", "sha256:" + "a".repeat(64));
    recording.put("debugSessionId", "stress-session");
    recording.put("debugSessionChecksum", "sha256:" + "b".repeat(64));
    recording.put("startTick", 0);
    recording.put("endTick", 500);
    ObjectNode curation = document.putObject("curation");
    curation.put("curationId", curationId.toString());
    curation.put("version", 1);
    curation.put("checksum", "sha256:" + "c".repeat(64));
    ObjectNode compiler = document.putObject("compiler");
    compiler.put("version", "stress");
    compiler.put("schemaVersion", 1);
    ObjectNode runtime = document.putObject("runtime");
    runtime.put("xrspsAdapterVersion", liveIdentity.xrspsAdapterVersion());
    runtime.put("xrspsBuildCommit", buildCommit);
    runtime.put("cacheRevision", liveIdentity.cacheRevision());
    runtime.put("contentRevision", liveIdentity.contentRevision());
    ObjectNode determinism = document.putObject("determinism");
    determinism.put("seed", "stress-seed");
    determinism.put("clockOrigin", "2026-01-01T00:00:00.000Z");
    determinism.put("tickDurationMs", 600);
    determinism.put("idNamespace", "stress");
    ObjectNode initialState = document.putObject("initialState");
    initialState.put("worldId", 0);
    ObjectNode player = initialState.putObject("player");
    ObjectNode position = player.putObject("position");
    position.put("x", 100);
    position.put("y", 100);
    position.put("plane", 0);
    player.putObject("skills").putObject("fishing").put("level", 30).put("xp", 6000);
    player.putArray("inventory");
    player.putArray("equipment");
    ArrayNode npcs = initialState.putArray("npcs");
    ObjectNode spot = npcs.addObject();
    spot.put("stableId", "spot:stress");
    spot.put("npcId", 1530);
    spot.putObject("position").put("x", 103).put("y", 101).put("plane", 0);
    spot.put("spotType", "net");
    ArrayNode actions = document.putArray("actions");
    for (int index = 0; index < actionCount; index++) {
      actions.addObject().put("type", "WAIT_UNTIL_TICK").put("tick", 2L * (index + 1));
    }
    document.putArray("expectations");
    document.putObject("tolerances");
    document.put("controlAllowed", false);
    return document;
  }

  private ReplayRunResponse createRun(UUID manifestId, String key) {
    ReplayRunResponse response =
        runService.create(
            new CreateReplayRunRequest(
                ReplayMode.CAUSAL_SIMULATION,
                manifestId.toString(),
                key + "-" + UUID.randomUUID(),
                ReplayStopPolicy.STOP_ON_FIRST_HARD),
            "stress-owner");
    createdRunIds.add(response.runId());
    return response;
  }

  private ReplayRun awaitTerminal(UUID runId, Duration timeout) {
    return awaitTerminal(worker, runId, timeout);
  }

  private ReplayRun awaitTerminal(ReplayWorker runner, UUID runId, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      runner.poll();
      ReplayRun run = runRepository.findById(runId).orElse(null);
      if (run != null && run.isTerminal()) {
        return run;
      }
      try {
        Thread.sleep(35);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for run " + runId, interrupted);
      }
    }
    throw new AssertionError(
        "Run did not become terminal: " + runId + "\n" + databaseDiagnostics());
  }

  private UUID[] seedExpiredLeaseAttempt(String key) {
    UUID runId =
        createRun(seedPublishedManifest(key, 0, liveIdentity.xrspsBuildCommit()), key).runId();
    ReplayRunService.ClaimResult claim =
        tx().execute(status -> runService.claimAtomically(runId, "recovery-seeder"));
    assertNotNull(claim);
    tx().executeWithoutResult(
            status -> {
              LocalDateTime now = runRepository.currentTimestamp();
              ReplayRun run = runRepository.findByIdForUpdate(runId).orElseThrow();
              run.setState(ReplayRunState.RUNNING);
              run.setPhase(ReplayRunPhase.EXECUTE);
              run.setStartedAt(now);
              run.setXrspsAdapterVersion(liveIdentity.xrspsAdapterVersion());
              run.setXrspsBuildCommit(liveIdentity.xrspsBuildCommit());
              run.setCacheRevision(liveIdentity.cacheRevision());
              run.setContentRevision(liveIdentity.contentRevision());
              runRepository.saveAndFlush(run);
              entityManager
                  .createNativeQuery(
                      "UPDATE replay_attempts SET lease_expires_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = :id")
                  .setParameter("id", claim.attempt().getId())
                  .executeUpdate();
            });
    return new UUID[] {runId, claim.attempt().getId()};
  }

  private UUID[] seedExpiringRaceRun(String key) {
    UUID[] ids = seedExpiredLeaseAttempt(key);
    tx().executeWithoutResult(
            status ->
                entityManager
                    // The race barrier must never start with an unexpired lease that can lapse
                    // between
                    // the recovery eligibility check and the normal worker fence check. Start from
                    // a
                    // definitively expired lease so exactly one of the two fenced terminalizers can
                    // win; the barrier still makes their entry to the race concurrent.
                    .createNativeQuery(
                        "UPDATE replay_attempts SET lease_expires_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = :id")
                    .setParameter("id", ids[1])
                    .executeUpdate());
    return ids;
  }

  private UUID seedTerminalRunWithoutReport() {
    UUID runId =
        createRun(
                seedPublishedManifest(
                    "terminal-" + UUID.randomUUID(), 0, liveIdentity.xrspsBuildCommit()),
                "terminal")
            .runId();
    ReplayRunService.ClaimResult claim =
        tx().execute(status -> runService.claimAtomically(runId, "terminal-seeder"));
    assertNotNull(claim);
    tx().executeWithoutResult(
            status -> {
              LocalDateTime now = runRepository.currentTimestamp();
              ReplayRun run = runRepository.findByIdForUpdate(runId).orElseThrow();
              runService.applyTransition(
                  run, ReplayRunState.FAILED, ReplayRunPhase.FINALIZE_REPORT, now);
              run.setOutcome(ReplayOutcome.INFRASTRUCTURE_FAILURE);
              run.setTerminalReason("STRESS_SEEDED");
              run.setStartedAt(now);
              run.setTerminalAt(now);
              run.setXrspsAdapterVersion(liveIdentity.xrspsAdapterVersion());
              run.setXrspsBuildCommit(liveIdentity.xrspsBuildCommit());
              run.setCacheRevision(liveIdentity.cacheRevision());
              run.setContentRevision(liveIdentity.contentRevision());
              runRepository.saveAndFlush(run);
              ReplayAttempt attempt =
                  attemptRepository.findByIdForUpdate(claim.attempt().getId()).orElseThrow();
              attempt.setState(ReplayRunState.FAILED.name());
              attempt.setTerminalAt(now);
              attempt.setLeaseExpiresAt(null);
              attemptRepository.saveAndFlush(attempt);
            });
    return runId;
  }

  private void recoverClaimedRun(UUID runId) {
    ReplayAttempt attempt =
        attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId).orElseThrow();
    tx().executeWithoutResult(
            status ->
                entityManager
                    .createNativeQuery(
                        "UPDATE replay_attempts SET lease_expires_at = CURRENT_TIMESTAMP - interval '1 second' "
                            + "WHERE id = :attemptId AND state = 'RUNNING'")
                    .setParameter("attemptId", attempt.getId())
                    .executeUpdate());
    assertEquals(
        java.util.Optional.of(ReplayRunState.FAILED), recoveryService.recover(attempt.getId()));
    assertTrue(runRepository.findById(runId).orElseThrow().isTerminal());
    assertTrue(reportRepository.findByRunId(runId).isPresent());
  }

  private LocalDateTime databaseTimestamp() {
    return tx().execute(status -> runRepository.currentTimestamp());
  }

  private Instant databaseInstant() {
    return databaseTimestamp().atZone(ZoneOffset.UTC).toInstant();
  }

  private UUID seedCuration(String key) {
    UUID id = UUID.randomUUID();
    tx().executeWithoutResult(
            status ->
                entityManager
                    .createNativeQuery(
                        "INSERT INTO recording_curations (id, recording_id, debug_session_id, version, state, role, content_json, content_checksum, created_by) VALUES (:id, :recording, :session, 1, 'PUBLISHED', 'TRAIN', '{}'::jsonb, :checksum, 'stress')")
                    .setParameter("id", id)
                    .setParameter("recording", "compiler-recording-" + key)
                    .setParameter("session", "compiler-session-" + key)
                    .setParameter("checksum", "sha256:" + "c".repeat(64))
                    .executeUpdate());
    return id;
  }

  private ScenarioManifest manifestEntity(UUID curationId, String checksum, int repetition) {
    ScenarioManifest manifest = new ScenarioManifest();
    manifest.setCurationId(curationId);
    manifest.setRecordingId("compiler-recording-" + repetition);
    manifest.setDebugSessionId("compiler-session-" + repetition);
    manifest.setRecordingChecksum("sha256:" + "a".repeat(64));
    manifest.setDebugSessionChecksum("sha256:" + "b".repeat(64));
    manifest.setCompilerVersion("stress");
    manifest.setCompilerSchemaVersion(1);
    manifest.setXrspsAdapterVersion(liveIdentity.xrspsAdapterVersion());
    manifest.setXrspsBuildCommit(liveIdentity.xrspsBuildCommit());
    manifest.setCacheRevision(liveIdentity.cacheRevision());
    manifest.setContentRevision(liveIdentity.contentRevision());
    manifest.setSeed("stress-seed");
    manifest.setFixedClockOrigin(LocalDateTime.of(2026, 1, 1, 0, 0));
    manifest.setStartTick(0);
    manifest.setEndTick(1);
    ObjectNode json =
        mapper.createObjectNode().put("manifestVersion", 1).put("manifestChecksum", checksum);
    manifest.setManifestJson(json);
    manifest.setManifestChecksum(checksum);
    manifest.setCompleteness("COMPLETE");
    manifest.setPublishState("PUBLISHED");
    manifest.setCreatedAt(databaseTimestamp());
    return manifest;
  }

  // ── catalog race fixture ──

  private void ensureCatalogTables() {
    tx().executeWithoutResult(
            status -> {
              entityManager
                  .createNativeQuery(
                      "CREATE TABLE IF NOT EXISTS recording_sessions (recording_id VARCHAR(255) PRIMARY KEY)")
                  .executeUpdate();
              entityManager
                  .createNativeQuery(
                      "CREATE TABLE IF NOT EXISTS debug_sessions (id BIGSERIAL PRIMARY KEY, recording_id VARCHAR(255), session_id VARCHAR(255), account_name VARCHAR(255), script_name VARCHAR(255), start_time TIMESTAMP NOT NULL, end_time TIMESTAMP, start_tick BIGINT, end_tick BIGINT)")
                  .executeUpdate();
              entityManager
                  .createNativeQuery(
                      "CREATE TABLE IF NOT EXISTS tick_snapshots (id BIGSERIAL PRIMARY KEY, session_id VARCHAR(255), tick BIGINT, player_state JSONB)")
                  .executeUpdate();
              entityManager
                  .createNativeQuery(
                      "CREATE TABLE IF NOT EXISTS tick_logs (id BIGSERIAL PRIMARY KEY, session_id VARCHAR(255), tick BIGINT)")
                  .executeUpdate();
            });
  }

  private void seedCatalogRows(int repetition) {
    tx().executeWithoutResult(
            status -> {
              entityManager
                  .createNativeQuery(
                      "TRUNCATE tick_logs, tick_snapshots, debug_sessions, recording_sessions RESTART IDENTITY CASCADE")
                  .executeUpdate();
              insertCatalogSession(
                  "newest-" + repetition,
                  "recording-newest-" + repetition,
                  LocalDateTime.of(2026, 8, 28, 4, 0));
              insertCatalogSession(
                  "older-" + repetition,
                  "recording-older-" + repetition,
                  LocalDateTime.of(2026, 8, 28, 3, 0));
            });
  }

  private void insertCatalogSession(String sessionId, String recordingId, LocalDateTime start) {
    entityManager
        .createNativeQuery("INSERT INTO recording_sessions (recording_id) VALUES (:recording)")
        .setParameter("recording", recordingId)
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "INSERT INTO debug_sessions (recording_id, session_id, account_name, script_name, start_time, end_time, start_tick, end_tick) VALUES (:recording, :session, 'stress', 'stress', :start, :end, 0, 10)")
        .setParameter("recording", recordingId)
        .setParameter("session", sessionId)
        .setParameter("start", start)
        .setParameter("end", start.plusMinutes(1))
        .executeUpdate();
  }

  private static LocalDateTime asLocalDateTime(Object value) {
    if (value instanceof LocalDateTime local) {
      return local;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    throw new IllegalArgumentException("Unexpected timestamp type: " + value);
  }

  // ── lifecycle and assertions ──

  private void assertNoActiveLeasesOrStaging(List<UUID> runIds) {
    for (UUID runId : new HashSet<>(runIds)) {
      ReplayRun run = runRepository.findById(runId).orElse(null);
      if (run == null) {
        continue;
      }
      if (run.isTerminal()) {
        ReplayAttempt latest =
            attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(runId).orElse(null);
        if (latest != null) {
          assertTrue(!"RUNNING".equals(latest.getState()), "active attempt leaked for " + runId);
          assertTrue(latest.getLeaseExpiresAt() == null, "lease leaked for " + runId);
        }
      }
      assertTrue(
          artifactRefRepository.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING).isEmpty(),
          "staging reference leaked for " + runId);
    }
    long activeAttempts =
        scalarCount(
            "SELECT COUNT(*) FROM replay_attempts "
                + "WHERE state = 'RUNNING' OR lease_expires_at IS NOT NULL");
    long nonTerminalRuns =
        scalarCount(
            "SELECT COUNT(*) FROM replay_runs "
                + "WHERE state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')");
    long stagingReferences =
        scalarCount("SELECT COUNT(*) FROM artifact_refs WHERE state = 'STAGING'");
    assertEquals(0L, activeAttempts, "no active replay leases may remain after a stress phase");
    assertEquals(0L, nonTerminalRuns, "no temporary claim may remain after a stress phase");
    assertEquals(
        0L, stagingReferences, "no database staging reference may remain after a stress phase");
    assertNoFilesystemStaging();
  }

  /** A clean DB reference is insufficient: no incomplete temporary writes may remain in storage. */
  private void assertNoFilesystemStaging() {
    if (artifactRoot != null && Files.exists(artifactRoot)) {
      try (var paths = Files.walk(artifactRoot)) {
        List<Path> files =
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .toList();
        assertTrue(files.isEmpty(), "incomplete artifact files leaked: " + files);
      } catch (IOException failure) {
        throw new AssertionError("Could not inspect artifact staging tree", failure);
      }
    }
  }

  private long scalarCount(String sql) {
    return tx().execute(
            status ->
                ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
  }

  private long countProcessesContaining(String token) {
    return ProcessHandle.allProcesses()
        .map(handle -> handle.info().commandLine().orElse(""))
        .filter(command -> command.toLowerCase().contains(token.toLowerCase()))
        .count();
  }

  private boolean hasProcessContaining(String token) {
    return countProcessesContaining(token) > 0;
  }

  private void waitForNoAdditionalProcesses(long before, String token) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline && countProcessesContaining(token) > before) {
      Thread.sleep(50);
    }
    assertTrue(countProcessesContaining(token) <= before, "process leak for " + token);
  }

  private Path writeFixture(String name, String source) throws IOException {
    Path directory = RUNTIME_FIXTURE_ROOT.resolve(name + "-" + UUID.randomUUID()).toAbsolutePath();
    Files.createDirectories(directory);
    Path path = directory.resolve(name + ".js");
    // The durable worker rejects placeholders just as production does.  Render the same live
    // identity-certifiable synthetic identity into protocol fixtures so each injected failure
    // reaches its intended
    // lifecycle boundary (reset, step, or snapshot) instead of failing early at capabilities.
    String rendered =
        source
            .replace("__XRSPS_ADAPTER__", jsString(liveIdentity.xrspsAdapterVersion()))
            .replace("__XRSPS_BUILD__", jsString(liveIdentity.xrspsBuildCommit()))
            .replace("__CACHE_REVISION__", jsString(liveIdentity.cacheRevision()))
            .replace("__CONTENT_REVISION__", jsString(liveIdentity.contentRevision()));
    Files.writeString(path, rendered, StandardCharsets.UTF_8);
    return path;
  }

  /**
   * Wraps the certified worker from a unique fixture path so process-release evidence is scoped.
   */
  private Path writeSyntheticWorkerLauncher(String name) throws IOException {
    Path directory =
        RUNTIME_FIXTURE_ROOT
            .resolve("artifact-worker-" + name + "-" + UUID.randomUUID())
            .toAbsolutePath();
    Files.createDirectories(directory);
    Path launcher = directory.resolve("worker-launcher-" + UUID.randomUUID() + ".js");
    Files.writeString(
        launcher,
        "require(" + jsString(Path.of(WORKER_ENTRYPOINT).toAbsolutePath().toString()) + ");\n",
        StandardCharsets.UTF_8);
    return launcher;
  }

  private static String jsString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private ReplayProperties certificationProperties(Path root) {
    ReplayProperties result = new ReplayProperties();
    result.setEnabled(true);
    result.getWorker().setEnabled(true);
    result.getWorker().setConcurrency(1);
    result.getWorker().setLeaseDurationSeconds(30);
    result.getWorker().setHeartbeatSeconds(5);
    result.getWorker().setRunTimeoutSeconds(300);
    result.getWorker().setMissingReportRepairEnabled(false);
    result.getArtifacts().setRoot(root.toString());
    ReplayProperties.Xrsps xrsps = result.getXrsps();
    xrsps.setExecutable(WORKER_EXECUTABLE);
    xrsps.setEntrypoint(WORKER_ENTRYPOINT);
    xrsps.setWorkingDirectory(WORKER_WORKING_DIRECTORY);
    xrsps.setExpectedAdapterVersion(liveIdentity.xrspsAdapterVersion());
    xrsps.setExpectedBuildCommit(liveIdentity.xrspsBuildCommit());
    xrsps.setExpectedCacheRevision(liveIdentity.cacheRevision());
    xrsps.setExpectedContentRevision(liveIdentity.contentRevision());
    xrsps.setStartupTimeoutMs(20_000);
    xrsps.setRequestTimeoutMs(30_000);
    xrsps.setShutdownTimeoutMs(5_000);
    return result;
  }

  private ReplayProperties.Xrsps xrspsConfig(Path entrypoint, long timeout) {
    ReplayProperties.Xrsps xrsps = new ReplayProperties.Xrsps();
    xrsps.setExecutable(WORKER_EXECUTABLE);
    xrsps.setEntrypoint(entrypoint.toAbsolutePath().toString());
    xrsps.setWorkingDirectory(entrypoint.toAbsolutePath().getParent().toString());
    xrsps.setRequestTimeoutMs(timeout);
    xrsps.setShutdownTimeoutMs(1500);
    return xrsps;
  }

  private String databaseDiagnostics() {
    StringBuilder output = new StringBuilder("PostgreSQL diagnostics:\n");
    try {
      List<?> activity =
          tx().execute(
                  status ->
                      entityManager
                          .createNativeQuery(
                              "SELECT pid, state, wait_event_type, wait_event, left(query, 160) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()")
                          .getResultList());
      activity.forEach(
          row -> {
            if (row instanceof Object[] values) {
              output.append("  ").append(Arrays.deepToString(values)).append('\n');
            } else {
              output.append("  ").append(String.valueOf(row)).append('\n');
            }
          });
    } catch (RuntimeException failure) {
      output.append("  activity unavailable: ").append(failure.getMessage()).append('\n');
    }
    return output.toString();
  }

  private TransactionTemplate tx() {
    return tx;
  }

  private void writeCertificationJson(String filename, JsonNode document) throws IOException {
    Path directory = CERTIFICATION_OUTPUT_DIRECTORY;
    Files.createDirectories(directory);
    String serialized = mapper.writeValueAsString(document);
    JsonNode reparsed = mapper.readTree(serialized);
    assertNotNull(reparsed);
    Files.writeString(directory.resolve(filename), serialized, StandardCharsets.UTF_8);
  }

  private static String envOrDefault(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String systemPropertyOrDefault(String key, String fallback) {
    String value = System.getProperty(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Path createMigrationDirectory() {
    try {
      Path directory = Files.createTempDirectory("replay-stress-migrations-");
      for (String migration : REPLAY_MIGRATIONS) {
        try (var input =
            ReplayStressCertificationTest.class.getResourceAsStream("/db/migration/" + migration)) {
          if (input == null) {
            throw new IllegalStateException("Missing migration resource " + migration);
          }
          Files.copy(input, directory.resolve(migration), StandardCopyOption.REPLACE_EXISTING);
        }
      }
      return directory;
    } catch (IOException e) {
      throw new IllegalStateException("Could not stage replay migrations", e);
    }
  }

  private record CapabilityCapture(JsonNode capabilities, RuntimeIdentity identity) {}

  private record RunEvidence(
      UUID runId,
      String chainHash,
      List<String> stateHashes,
      List<String> eventPayloadHashes,
      List<Map<String, Object>> checkpointRows,
      List<String> artifactRows,
      List<String> finalizedArtifactReadback,
      JsonNode trace) {}

  private record FailureObservation(String code, String message) {}

  private record DurableFailureObservation(
      ReplayRun run,
      ReplayReport report,
      boolean leaseReleased,
      boolean stagingReferencesEmpty,
      int finalizedReferenceCount,
      boolean finalizedReferencesValid) {}

  private record PublisherFixture(
      UUID runId,
      ReplayFence fence,
      ReplayWorkerPersistenceService.FinalizationReservation reservation,
      ReplayReport report,
      String traceChecksum) {}

  private record WorkerArtifactFailureObservation(
      UUID runId,
      String error,
      String runState,
      String terminalReason,
      String reportOutcome,
      boolean reportPresent,
      boolean leaseReleased,
      boolean stagingReferencesEmpty,
      int finalizedReferenceCount,
      boolean finalizedReferencesValid,
      boolean processesReleased) {

    boolean falseSuccess() {
      return ReplayRunState.SUCCEEDED.name().equals(runState)
          || ReplayOutcome.PASSED.name().equals(reportOutcome);
    }

    boolean deterministicWith(WorkerArtifactFailureObservation other) {
      return error.equals(other.error)
          && runState.equals(other.runState)
          && java.util.Objects.equals(terminalReason, other.terminalReason)
          && reportOutcome.equals(other.reportOutcome)
          && reportPresent == other.reportPresent
          && leaseReleased == other.leaseReleased
          && stagingReferencesEmpty == other.stagingReferencesEmpty
          && finalizedReferenceCount == other.finalizedReferenceCount
          && finalizedReferencesValid == other.finalizedReferencesValid;
    }

    boolean recoveryOrFailClosed() {
      return !ReplayRunState.SUCCEEDED.name().equals(runState)
          && !ReplayOutcome.PASSED.name().equals(reportOutcome)
          && reportPresent
          && leaseReleased
          && stagingReferencesEmpty;
    }
  }

  private record SnapshotFailureObservation(
      UUID parentRunId,
      UUID childRunId,
      String error,
      String runState,
      String terminalReason,
      String reportOutcome,
      boolean reportPresent,
      boolean leaseReleased,
      boolean stagingReferencesEmpty,
      int finalizedReferenceCount,
      boolean finalizedReferencesValid,
      boolean processesReleased,
      boolean parentSucceeded) {

    boolean falseSuccess() {
      return !ReplayRunState.FAILED.name().equals(runState)
          || !ReplayOutcome.FAILED.name().equals(reportOutcome);
    }

    boolean deterministicWith(SnapshotFailureObservation other) {
      return error.equals(other.error)
          && runState.equals(other.runState)
          && terminalReason.equals(other.terminalReason)
          && reportOutcome.equals(other.reportOutcome)
          && reportPresent == other.reportPresent
          && leaseReleased == other.leaseReleased
          && stagingReferencesEmpty == other.stagingReferencesEmpty
          && finalizedReferenceCount == other.finalizedReferenceCount
          && finalizedReferencesValid == other.finalizedReferencesValid
          && parentSucceeded == other.parentSucceeded;
    }
  }

  private record UniquenessConflictObservation(
      boolean observed, String sqlState, String constraint) {}

  private static final class FixtureFailure extends RuntimeException {
    private final String code;

    private FixtureFailure(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  /** Test-only decorator that mutates bytes at the production publication boundary. */
  private static final class FaultingArtifactService extends ReplayArtifactService {

    private final ArtifactRefRepository refs;
    private final ReplayProperties properties;
    private final boolean corrupt;
    private final AtomicBoolean injected = new AtomicBoolean();

    private FaultingArtifactService(
        ArtifactRefRepository refs, ReplayProperties properties, boolean corrupt) {
      super(refs, properties);
      this.refs = refs;
      this.properties = properties;
      this.corrupt = corrupt;
    }

    @Override
    public ArtifactPublicationReceipt publishArtifacts(UUID runId) {
      if (injected.compareAndSet(false, true)) {
        ArtifactRef target =
            refs.findByRunIdAndState(runId, ArtifactRef.STATE_STAGING).stream()
                .filter(ref -> ref.getKind() == ArtifactKind.TRACE)
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("fault injection requires a staged TRACE artifact"));
        Path staged =
            Path.of(properties.getArtifacts().getRoot())
                .resolve("staging")
                .resolve(runId.toString())
                .resolve(target.getKind().name())
                .resolve(target.getChecksum().substring("sha256:".length()));
        try {
          if (!corrupt) {
            Files.deleteIfExists(staged);
          } else {
            byte[] bytes = Files.readAllBytes(staged);
            if (bytes.length == 0) {
              throw new IOException("cannot corrupt an empty staged artifact");
            }
            bytes[0] ^= 0x55;
            Files.write(staged, bytes);
          }
        } catch (IOException failure) {
          throw new ReplayException(
              ReplayErrorCode.ARTIFACT_STORAGE_UNAVAILABLE,
              org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
              true,
              "Fault injection could not alter staged artifact",
              null,
              null,
              failure);
        }
      }
      return super.publishArtifacts(runId);
    }
  }

  @TestConfiguration
  @org.springframework.context.annotation.Profile("replay-stress-certification")
  static class StressServices {

    @Bean
    ReplayLeaseRenewalService stressLeaseRenewalService(
        ReplayAttemptRepository repository, ReplayProperties properties) {
      return new ReplayLeaseRenewalService(repository, properties);
    }

    @Bean
    ScenarioManifestInsertService stressManifestInsertService(
        ScenarioManifestRepository repository) {
      return new ScenarioManifestInsertService(repository);
    }
  }

  // Fixture scripts intentionally write diagnostics only to stderr and preserve newline-delimited
  // JSON behavior on stdout. They are stored under build/replay-certification for post-failure
  // inspection and are never used as an authoritative worker.
  private static final String SECRET_STDERR_MARKER = "SECRET_MARKER_7f9b2e";

  private static final String EXIT_BEFORE_CAPABILITIES =
      "process.stderr.write('" + SECRET_STDERR_MARKER + "\\n'); process.exit(17);";

  private static final String EXIT_DURING_RESET =
      "const rl=require('readline').createInterface({input:process.stdin});"
          + "rl.on('line',line=>{const m=JSON.parse(line);"
          + "if(m.method==='capabilities')process.stdout.write(JSON.stringify({id:m.id,ok:true,result:{capabilities:{certifiable:true,controlAllowed:false,openRuneAvailable:false,fidelity:'SYNTHETIC_CONTRACT',supportedActions:['WAIT_UNTIL_TICK','INTERACT_NPC'],modes:{observedPlayback:false,policyReplay:false,causalSimulation:true},xrsps:{adapterVersion:__XRSPS_ADAPTER__,buildCommit:__XRSPS_BUILD__,cacheRevision:__CACHE_REVISION__,contentRevision:__CONTENT_REVISION__}}}})+'\\n');"
          + "else if(m.method==='reset')process.exit(18);});";

  private static final String EXIT_BETWEEN_STEP_AND_SNAPSHOT =
      "const rl=require('readline').createInterface({input:process.stdin});"
          + "rl.on('line',line=>{const m=JSON.parse(line);"
          + "if(m.method==='capabilities')process.stdout.write(JSON.stringify({id:m.id,ok:true,result:{capabilities:{certifiable:true,controlAllowed:false,openRuneAvailable:false,fidelity:'SYNTHETIC_CONTRACT',supportedActions:['WAIT_UNTIL_TICK','INTERACT_NPC'],modes:{observedPlayback:false,policyReplay:false,causalSimulation:true},xrsps:{adapterVersion:__XRSPS_ADAPTER__,buildCommit:__XRSPS_BUILD__,cacheRevision:__CACHE_REVISION__,contentRevision:__CONTENT_REVISION__}}}})+'\\n');"
          + "else if(m.method==='reset')process.stdout.write(JSON.stringify({id:m.id,ok:true,result:{initialTick:0}})+'\\n');"
          + "else if(m.method==='step'){process.stdout.write(JSON.stringify({id:m.id,ok:true,result:{terminalTick:1,observation:{},events:[]}})+'\\n');process.exit(19);}});";

  private static final String MALFORMED_STDOUT =
      "process.stdin.on('data',()=>process.stdout.write('not-json\\n'));setInterval(()=>{},60000);";

  private static final String REQUEST_TIMEOUT = "process.stdin.resume();setInterval(()=>{},60000);";
}
