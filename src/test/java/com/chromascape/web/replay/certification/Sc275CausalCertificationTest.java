package com.chromascape.web.replay.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.capability.XrspsStartupValidator;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.dto.BranchReplayRunRequest;
import com.chromascape.web.replay.run.dto.CreateReplayRunRequest;
import com.chromascape.web.replay.run.dto.ReplayRunResponse;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import com.chromascape.web.replay.worker.ReplayExpiredAttemptRecoveryService;
import com.chromascape.web.replay.worker.ReplayLeaseHeartbeat;
import com.chromascape.web.replay.worker.ReplayLeaseRenewalService;
import com.chromascape.web.replay.worker.ReplayWorker;
import com.chromascape.web.replay.worker.ReplayWorkerPersistenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** SC-275 native Fedora causal persistence/restore proof using the production worker. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DataJpaTest(
    properties = {
      // Flyway owns the schema; Hibernate may NEVER repair it behind Flyway's back.
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.main.lazy-initialization=true",
      "spring.datasource.hikari.maximum-pool-size=16",
      "spring.datasource.hikari.connection-timeout=120000",
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("replay-certification")
@org.springframework.test.context.ContextConfiguration(
    classes = Sc275CausalCertificationTest.CertificationSliceApplication.class)
@Import(ReplayCertificationTest.CertificationServices.class)
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class Sc275CausalCertificationTest {

  /**
   * EXPLICIT slice application so this suite boots INDEPENDENTLY of the wider application and only
   * the replay entities covered by the replay migrations enter schema validation.
   */
  @Configuration(proxyBeanMethods = false)
  @Profile("replay-certification")
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  @org.springframework.boot.autoconfigure.domain.EntityScan(
      basePackages = "com.chromascape.web.replay")
  @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
      basePackages = "com.chromascape.web.replay")
  static class CertificationSliceApplication {}

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("sc275_fedora_certification")
          .withUsername("test")
          .withPassword("test")
          // Docker Desktop/WSL can deliver the PostgreSQL log line before a consumer attaches.
          // Port readiness is the bounded startup contract and does not depend on log delivery.
          .waitingFor(HostPortWaitStrategy.postgres());

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.locations", Sc275CausalCertificationTest::replayMigrations);
  }

  private static String replayMigrations() {
    try {
      java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sc275-fedora-migrations");
      String[] scripts = {
        "V10__recording_archive.sql",
        "V11__replay_curation_and_manifest.sql",
        "V12__replay_suites_and_runs.sql",
        "V13__replay_events_checkpoints_and_hashes.sql",
        "V14__replay_mismatches_reports_and_artifacts.sql",
        "V15__replay_schema_alignment.sql",
        "V17__replay_artifact_reference_reuse.sql",
        "V18__replay_checkpoint_artifact_checksum.sql",
        "V19__reconcile_replay_checkpoint_artifacts.sql",
        "V20__replay_finalization_reservation.sql",
        "V24__replay_recording_actions.sql",
        "V30__replay_pre_execution_reports.sql",
        "V31__replay_playback_catalog_pagination.sql",
        "V25__replay_v2_metadata.sql",
        "V26__replay_v2_request_identity.sql",
        "V63__sc275_causal_restore_bindings.sql",
        "V64__sc275_causal_projection_samples.sql"
      };
      for (String script : scripts) {
        try (var in =
            Sc275CausalCertificationTest.class.getResourceAsStream("/db/migration/" + script)) {
          java.nio.file.Files.copy(
              in, dir.resolve(script), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
      return "filesystem:" + dir;
    } catch (Exception error) {
      throw new IllegalStateException("could not prepare replay migrations", error);
    }
  }

  // ── worker prerequisites (hard, never skip) ──

  /** Path to the freshly built certifiable worker main.js, from env or the canonical dev layout. */
  private static final String WORKER_ENTRYPOINT =
      System.getenv("REPLAY_XRSPS_ENTRYPOINT") != null
          ? System.getenv("REPLAY_XRSPS_ENTRYPOINT")
          : "C:\\Dev\\IdeaProjects\\xrsps-typescript\\packages\\r"
              + "eplay-kernel\\dist\\worker\\main.js";

  private static final String WORKER_WORKING_DIRECTORY =
      System.getenv("REPLAY_XRSPS_WORKING_DIRECTORY") != null
          ? System.getenv("REPLAY_XRSPS_WORKING_DIRECTORY")
          : "C:\\Dev\\IdeaProjects\\xrsps-typescript";

  private static Path artifactRoot;
  private static RuntimeIdentity liveIdentity;
  private static boolean hostChecked;
  private static boolean workerChecked;
  private static String xrpsRepoRoot;
  private static String xrpsRepoHeadCommit;
  private static CapabilityManifest liveCapabilityManifest;

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
  @Autowired ReplayExpiredAttemptRecoveryService recoveryService;
  @Autowired ReplayReportService reportService;
  @Autowired ReplayCapabilityService capabilityService;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;

  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalProjectionService projection = new CanonicalProjectionService();

  private ReplayProperties properties;
  private ReplayWorker worker;
  private TransactionTemplate tx;

  private final AtomicInteger manifestCounter = new AtomicInteger();

  /** Fails (never skips) when the host is not native Linux. */
  @BeforeAll
  static void requireFedoraHost() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.equals("linux")) {
      throw new IllegalStateException(
          "replayFedoraCertificationTest is Linux-only; refusing to run on '" + os + "'");
    }
    hostChecked = true;
  }

  /** Fails (never skips) when the worker is missing or not certifiable. */
  @BeforeAll
  static void requireCertifiableWorker() {
    if (!hostChecked) {
      requireFedoraHost();
    }
    Path entrypoint = Path.of(WORKER_ENTRYPOINT);
    if (!Files.isRegularFile(entrypoint)) {
      throw new IllegalStateException(
          "Certifiable worker entrypoint not found: "
              + WORKER_ENTRYPOINT
              + ". Build packages/replay-kernel and generate runtime identity first.");
    }
    liveIdentity = fetchLiveIdentity();
    if (liveIdentity == null) {
      throw new IllegalStateException(
          "The worker did not return a usable capability document; "
              + "a development build (certifiable:false) fails certification by design.");
    }
    if (liveCapabilityManifest.fidelity() != CapabilityManifest.Fidelity.XRSPS_AUTHORITATIVE) {
      throw new IllegalStateException(
          "This causal persistence battery requires the current worker's explicit"
              + " XRSPS_AUTHORITATIVE fishing profile; general game-fidelity certification remains"
              + " separate");
    }
    workerChecked = true;
    recordCertificationEnvironment();
  }

  /** Launches the worker in --capabilities mode and returns the live certifiable identity. */
  private static RuntimeIdentity fetchLiveIdentity() {
    ReplayProperties properties = new ReplayProperties();
    properties.getXrsps().setExecutable("node");
    properties.getXrsps().setEntrypoint(WORKER_ENTRYPOINT);
    properties.getXrsps().setWorkingDirectory(WORKER_WORKING_DIRECTORY);
    properties.getXrsps().setStartupTimeoutMs(20_000);
    properties.getXrsps().setRequestTimeoutMs(20_000);

    XrspsKernelProcess kernel = new XrspsKernelProcess(properties.getXrsps(), new ObjectMapper());
    try {
      kernel.start();
      KernelMessage caps = kernel.send("capabilities", null);
      if (caps.isError()) {
        throw new IllegalStateException(
            "xRSPS capability request failed: "
                + caps.error().path("code").asText("UNKNOWN")
                + " — "
                + caps.error().path("message").asText("no error message"));
      }
      JsonNode capability = caps.result().path("capabilities");
      if (capability.isMissingNode()) {
        capability = caps.result();
      }
      if (!capability.path("certifiable").asBoolean(false)) {
        throw new IllegalStateException(
            "xRSPS worker advertised certifiable=false; rebuild without the development identity");
      }
      JsonNode xrsps = capability.path("xrsps");
      RuntimeIdentity identity =
          new RuntimeIdentity(
              xrsps.path("adapterVersion").asText(null),
              xrsps.path("buildCommit").asText(null),
              xrsps.path("cacheRevision").asText(null),
              xrsps.path("contentRevision").asText(null));
      if (identity.xrspsAdapterVersion() == null
          || identity.xrspsAdapterVersion().isBlank()
          || identity.xrspsBuildCommit() == null
          || identity.xrspsBuildCommit().isBlank()
          || "dev".equals(identity.xrspsBuildCommit())
          || "headless-deterministic".equals(identity.xrspsBuildCommit())
          || identity.cacheRevision() == null
          || !identity.cacheRevision().matches("^sha256:[0-9a-f]{64}$")
          || identity.contentRevision() == null
          || !identity.contentRevision().matches("^sha256:[0-9a-f]{64}$")) {
        throw new IllegalStateException(
            "xRSPS capability contains an incomplete or malformed runtime identity: " + xrsps);
      }

      // ── PROOF 1: the identity must be the xRSPS repository's CURRENT HEAD ──
      // A stale worker build can never certify: the live buildCommit must resolve to the same
      // commit the repository points at, and the worktree must be clean (a dirty tree means the
      // built worker does not correspond to any commit at all).
      String repoRoot = gitLine(WORKER_WORKING_DIRECTORY, "rev-parse", "--show-toplevel");
      String headCommit = gitLine(repoRoot, "rev-parse", "HEAD");
      if (!headCommit.matches("^[0-9a-f]{40}$")) {
        throw new IllegalStateException(
            "xRSPS repository HEAD is not a 40-hex commit: '" + headCommit + "'");
      }
      if (!headCommit.equals(identity.xrspsBuildCommit())) {
        throw new IllegalStateException(
            "STALE WORKER BUILD: live capability buildCommit '"
                + identity.xrspsBuildCommit()
                + "' does not equal the xRSPS repository HEAD '"
                + headCommit
                + "' (resolved root: "
                + repoRoot
                + "). Rebuild the certifiable worker and regenerate its identity.");
      }
      String dirty = gitLine(repoRoot, "status", "--porcelain");
      if (!dirty.isBlank()) {
        throw new IllegalStateException(
            "DIRTY xRSPS WORKTREE at "
                + repoRoot
                + " — a certifiable identity cannot be proven against uncommitted sources:\n"
                + dirty);
      }
      xrpsRepoRoot = repoRoot;
      xrpsRepoHeadCommit = headCommit;

      // ── PROOF 2: the RAW capability JSON must pass the production strict validator ──
      // Manual field extraction is not a contract: the same document the worker returned must
      // survive XrspsStartupValidator.validateRawDocument + verifyVersions unchanged.
      ReplayProperties validatorProperties = new ReplayProperties();
      ReplayProperties.Xrsps validatorConfig = validatorProperties.getXrsps();
      validatorConfig.setExecutable("node");
      validatorConfig.setEntrypoint(WORKER_ENTRYPOINT);
      validatorConfig.setWorkingDirectory(WORKER_WORKING_DIRECTORY);
      validatorConfig.setExpectedAdapterVersion(identity.xrspsAdapterVersion());
      validatorConfig.setExpectedBuildCommit(identity.xrspsBuildCommit());
      validatorConfig.setExpectedCacheRevision(identity.cacheRevision());
      validatorConfig.setExpectedContentRevision(identity.contentRevision());
      XrspsStartupValidator validator =
          new XrspsStartupValidator(
              validatorProperties, new ReplayCapabilityService(), new ObjectMapper());
      List<String> rawProblems =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(
              validator, "validateRawDocument", capability);
      if (rawProblems == null || !rawProblems.isEmpty()) {
        throw new IllegalStateException(
            "RAW CAPABILITY DOCUMENT rejected by the production strict validator: " + rawProblems);
      }
      CapabilityManifest capabilityManifest =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(
              validator, "parseManifest", capability);
      List<String> versionProblems =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(
              validator, "verifyVersions", capabilityManifest, validatorConfig);
      if (versionProblems == null || !versionProblems.isEmpty()) {
        throw new IllegalStateException(
            "CAPABILITY VERSIONS rejected by the production strict validator: " + versionProblems);
      }
      liveCapabilityManifest = capabilityManifest;
      return identity;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to interrogate the certifiable xRSPS worker", e);
    } finally {
      try {
        kernel.close();
      } catch (RuntimeException ignored) {
        // best-effort process cleanup
      }
    }
  }

  /**
   * Writes the certification environment record (xRSPS commit, adapter version, revisions, git
   * HEAD, PostgreSQL image) for the Gradle task's doLast logging. The file is deleted by the task's
   * doFirst, so its presence proves the logged environment belongs to THIS run.
   */
  private static void recordCertificationEnvironment() {
    Path environmentDir = Path.of("build", "replay-fedora-certification");
    try {
      Files.createDirectories(environmentDir);
      Files.writeString(
          environmentDir.resolve("identity.properties"),
          "xrps.commit="
              + liveIdentity.xrspsBuildCommit()
              + "\n"
              + "xrps.adapterVersion="
              + liveIdentity.xrspsAdapterVersion()
              + "\n"
              + "xrps.cacheRevision="
              + liveIdentity.cacheRevision()
              + "\n"
              + "xrps.contentRevision="
              + liveIdentity.contentRevision()
              + "\n"
              + "worker.fidelity="
              + liveCapabilityManifest.fidelity()
              + "\n"
              + "worker.supportedActions="
              + liveCapabilityManifest.supportedActions()
              + "\n"
              + "evidence.class=sc275-causal-production-persistence\nhost.os="
              + System.getProperty("os.name")
              + "\n"
              + "xrps.gitHead="
              + xrpsRepoHeadCommit
              + "\n"
              + "xrps.repositoryRoot="
              + xrpsRepoRoot
              + "\n"
              + "postgres.image="
              + POSTGRES.getDockerImageName()
              + "\n");
    } catch (Exception e) {
      throw new IllegalStateException("could not record the certification environment", e);
    }
  }

  /** Runs a git command against the xRSPS checkout and returns its trimmed stdout. */
  private static String gitLine(String directory, String... args) {
    String[] command = new String[args.length + 3];
    command[0] = "git";
    command[1] = "-C";
    command[2] = directory;
    System.arraycopy(args, 0, command, 3, args.length);
    String description = "git " + String.join(" ", args) + " (in " + directory + ")";
    try {
      Process process = new ProcessBuilder(command).start();
      String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("git command timed out: " + description);
      }
      if (process.exitValue() != 0) {
        String stderr =
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        throw new IllegalStateException(
            "git command failed (exit "
                + process.exitValue()
                + "): "
                + description
                + ": "
                + stderr);
      }
      return stdout;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command interrupted: " + description, e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(
          "git command could not start (git required on PATH): " + description, e);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    artifactRoot = Files.createTempDirectory("sc275-fedora-artifacts");
    properties = certificationProperties(artifactRoot);
    worker =
        new ReplayWorker(
            runRepository,
            attemptRepository,
            runService,
            manifestRepository,
            checkpointRepository,
            artifactRefRepository,
            mismatchRepository,
            persistence,
            new ReplayLeaseHeartbeat(
                new ReplayLeaseRenewalService(attemptRepository, properties), properties),
            recoveryService,
            new com.chromascape.web.replay.artifact.ReplayArtifactService(
                artifactRefRepository, properties),
            new MismatchEngine(mismatchRepository, new CanonicalProjectionService()),
            reportService,
            new CanonicalProjectionService(),
            properties,
            mapper);
    tx = new TransactionTemplate(transactionManager);
    if (liveCapabilityManifest != null) {
      // Mirror the production startup validator's successful handshake in the slice bean. The
      // worker prerequisite is captured before the Spring test instance is initialized, so the
      // certification test must explicitly publish the same validated manifest to the service
      // used by the runtime eligibility assertion.
      capabilityService.updateFromHandshake(liveCapabilityManifest);
    }
  }

  @org.junit.jupiter.api.AfterEach
  void closeWorker() {
    if (worker != null) {
      worker.close();
    }
  }

  @AfterAll
  static void tearDownArtifacts() {
    if (artifactRoot != null) {
      try (var paths = Files.walk(artifactRoot)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
  }

  private static ReplayProperties certificationProperties(Path root) {
    ReplayProperties properties = new ReplayProperties();
    properties.setEnabled(true);
    properties.getWorker().setEnabled(true);
    properties.getWorker().setConcurrency(2);
    properties.getWorker().setLeaseDurationSeconds(30);
    properties.getWorker().setHeartbeatSeconds(10);
    properties.getWorker().setRunTimeoutSeconds(300);
    properties.getWorker().setMissingReportRepairEnabled(false);
    properties.getArtifacts().setRoot(root.toString());
    ReplayProperties.Xrsps xrsps = properties.getXrsps();
    xrsps.setExecutable("node");
    xrsps.setEntrypoint(WORKER_ENTRYPOINT);
    xrsps.setWorkingDirectory(WORKER_WORKING_DIRECTORY);
    xrsps.setExpectedAdapterVersion(liveIdentity.xrspsAdapterVersion());
    xrsps.setExpectedBuildCommit(liveIdentity.xrspsBuildCommit());
    xrsps.setExpectedCacheRevision(liveIdentity.cacheRevision());
    xrsps.setExpectedContentRevision(liveIdentity.contentRevision());
    xrsps.setStartupTimeoutMs(20_000);
    xrsps.setRequestTimeoutMs(30_000);
    xrsps.setShutdownTimeoutMs(5_000);
    return properties;
  }

  // ── manifest helpers ──

  /**
   * Builds the full scenario manifest document WITHOUT its checksum field, matching the worker's
   * {@code ScenarioManifest} contract. Runtime pins are EXACTLY the live identity.
   */
  private ObjectNode manifestDocumentWithoutChecksum(UUID manifestId) {
    final String recordingId = certificationRecordingId(manifestId);
    final String sessionId = certificationSessionId(manifestId);
    ObjectNode doc = mapper.createObjectNode();
    doc.put("manifestVersion", 2);
    doc.put("fidelity", "XRSPS_AUTHORITATIVE");
    doc.put("fidelityRequirement", "XRSPS_AUTHORITATIVE");
    doc.put("manifestId", manifestId.toString());
    ObjectNode rec = doc.putObject("recording");
    rec.put("recordingId", recordingId);
    rec.put("recordingChecksum", "sha256:" + "aa".repeat(32));
    rec.put("debugSessionId", sessionId);
    rec.put("debugSessionChecksum", "sha256:" + "bb".repeat(32));
    rec.put("startTick", 0);
    rec.put("endTick", 2000);
    ObjectNode cur = doc.putObject("curation");
    cur.put("curationId", UUID.randomUUID().toString());
    cur.put("version", 1);
    cur.put("checksum", "sha256:" + "cc".repeat(32));
    ObjectNode compiler = doc.putObject("compiler");
    compiler.put("version", "certification");
    compiler.put("schemaVersion", 2);
    ObjectNode runtime = doc.putObject("runtime");
    runtime.put("xrspsAdapterVersion", liveIdentity.xrspsAdapterVersion());
    runtime.put("xrspsBuildCommit", liveIdentity.xrspsBuildCommit());
    runtime.put("cacheRevision", liveIdentity.cacheRevision());
    runtime.put("contentRevision", liveIdentity.contentRevision());
    ObjectNode determinism = doc.putObject("determinism");
    determinism.put("seed", "certification-seed");
    determinism.put("clockOrigin", "2026-01-01T00:00:00.000Z");
    determinism.put("tickDurationMs", 600);
    determinism.put("idNamespace", "replay");
    ObjectNode initialState = doc.putObject("initialState");
    initialState.put("worldId", 0);
    initialState.put("collisionRevision", "sc275-certified-collision");
    ObjectNode player = initialState.putObject("player");
    ObjectNode pos = player.putObject("position");
    pos.put("x", 3080);
    pos.put("y", 3227);
    pos.put("plane", 0);
    ObjectNode skills = player.putObject("skills");
    ObjectNode fishing = skills.putObject("fishing");
    fishing.put("level", 1);
    fishing.put("xp", 0);
    player.putArray("inventory").addObject().put("slot", 0).put("itemId", 303).put("quantity", 1);
    player.putArray("equipment");
    ArrayNode npcs = initialState.putArray("npcs");
    ObjectNode spot = npcs.addObject();
    spot.put("stableId", "spot:a");
    spot.put("npcId", 1530);
    spot.put("npcTypeId", 1530);
    spot.put("spotId", "sea_small_net");
    spot.put("size", 1);
    ObjectNode spotPos = spot.putObject("position");
    spotPos.put("x", 3081);
    spotPos.put("y", 3227);
    spotPos.put("plane", 0);
    spot.put("spotType", "net");
    spot.put("stableId", "spot:0");
    for (int i = 1; i < 27; i++) {
      npcs.add(spot.deepCopy().put("stableId", "spot:" + i));
    }
    // Distinct targets exercise the live fishing state through the checkpoint cadence
    // (CHECKPOINT_INTERVAL_ACTIONS = 25) produces an INTERMEDIATE checkpoint after action index 24
    // (actions 25..26 still remain) plus the final isLast checkpoint. Branching from the
    // intermediate checkpoint forces the child to CONTINUE the chain with real actions instead of
    // merely restoring it.
    ArrayNode actions = doc.putArray("actions");
    for (int i = 0; i < 27; i++) {
      ObjectNode action = actions.addObject();
      action.put("type", "INTERACT_NPC");
      action.put("targetStableId", "spot:" + i);
      action.put("option", "net");
      action.put("sourceActionTick", i);
      action.put("sourceActionSequence", 0);
    }
    doc.putArray("expectations");
    doc.putObject("tolerances");
    doc.put("controlAllowed", false);
    return doc;
  }

  private static String certificationRecordingId(UUID manifestId) {
    return "cert-recording-" + manifestId;
  }

  private static String certificationSessionId(UUID manifestId) {
    return "cert-session-" + manifestId;
  }

  /**
   * Seeds the curation + published manifest rows with the REAL manifest JSON and the CORRECT
   * single-prefix checksum (sha256 over canonical bytes of the document WITHOUT the checksum field,
   * exactly as the compiler and the worker's {@code hashCanonical} do).
   */
  private UUID seedPublishedManifest() {
    return seedPublishedManifest(500000);
  }

  private UUID seedPublishedManifest(long endTick) {
    return tx.execute(
        status -> {
          final UUID curationId = UUID.randomUUID();
          UUID manifestId = UUID.randomUUID();
          final String recordingId = certificationRecordingId(manifestId);
          final String sessionId = certificationSessionId(manifestId);
          ObjectNode doc = manifestDocumentWithoutChecksum(manifestId);
          ((ObjectNode) doc.path("recording")).put("endTick", endTick);
          String checksum = projection.sha256Hex(projection.canonicalJsonBytes(doc));
          assertEquals(
              1,
              countOccurrences(checksum, "sha256:"),
              "the manifest checksum must carry exactly one sha256: prefix");
          doc.put("manifestChecksum", checksum);

          entityManager
              .createNativeQuery(
                  "INSERT INTO recording_curations (id, recording_id, debug_session_id, version,"
                      + " state, role, content_json, content_checksum, created_by)"
                      + " VALUES ('"
                      + curationId
                      + "', '"
                      + recordingId
                      + "', '"
                      + sessionId
                      + "', 1,"
                      + " 'PUBLISHED', 'TRAIN', '{}'::jsonb, 'sha256:"
                      + "c".repeat(64)
                      + "', 'certification')")
              .executeUpdate();
          entityManager
              .createNativeQuery(
                  "INSERT INTO scenario_manifests (id, curation_id, recording_id, debug_session_id,"
                      + " recording_checksum, debug_session_checksum, compiler_version,"
                      + " compiler_schema_version, xrsps_adapter_version, xrsps_build_commit,"
                      + " cache_revision, content_revision, seed, fixed_clock_origin, start_tick,"
                      + " end_tick, manifest_json, manifest_checksum, completeness, publish_state,"
                      + " manifest_version, fidelity, conversion_quality, action_coverage_pct,"
                      + " created_at) VALUES ('"
                      + manifestId
                      + "', '"
                      + curationId
                      + "', '"
                      + recordingId
                      + "', '"
                      + sessionId
                      + "', 'sha256:"
                      + "a".repeat(64)
                      + "', 'sha256:"
                      + "b".repeat(64)
                      + "', 'certification', 2, '"
                      + liveIdentity.xrspsAdapterVersion()
                      + "', '"
                      + liveIdentity.xrspsBuildCommit()
                      + "', '"
                      + liveIdentity.cacheRevision()
                      + "', '"
                      + liveIdentity.contentRevision()
                      + "', 'certification-seed', TIMESTAMP '2026-01-01 00:00:00', 0, "
                      + endTick
                      + ","
                      + " '"
                      + doc.toString().replace("'", "''")
                      + "'::jsonb, '"
                      + checksum
                      + "', 'COMPLETE', 'PUBLISHED', 2, 'XRSPS_AUTHORITATIVE', 'BEST_EFFORT', 100,"
                      + " now())")
              .executeUpdate();
          return manifestId;
        });
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }

  private ReplayRunResponse createParentRun(UUID manifestId) {
    return runService.create(
        new CreateReplayRunRequest(
            ReplayMode.CAUSAL_SIMULATION,
            manifestId.toString(),
            "cert-parent-" + UUID.randomUUID(),
            ReplayStopPolicy.STOP_ON_FIRST_HARD),
        "certification-owner");
  }

  private ReplayRunResponse createBranchRun(UUID parentRunId, UUID checkpointId, String branchKey) {
    return runService.branch(
        parentRunId,
        new BranchReplayRunRequest(
            checkpointId.toString(), branchKey, "cert-branch-" + UUID.randomUUID()),
        "certification-owner");
  }

  /** Drives one poll pass and waits (bounded) for the run to leave QUEUED/CLAIMED/RUNNING. */
  private ReplayRun awaitTerminal(UUID runId, int timeoutSeconds) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    long nextPollNanos = 0;
    while (System.nanoTime() < deadline) {
      // The worker polls a bounded oldest-first queue. Earlier tests deliberately leave queued
      // fixtures behind to prove their own fail-closed behavior, so keep polling until this
      // specific run is dispatched instead of assuming one pass reaches it.
      if (System.nanoTime() >= nextPollNanos) {
        worker.poll();
        nextPollNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
      }
      ReplayRun run = runRepository.findById(runId).orElse(null);
      if (run != null && run.getState().name().matches("SUCCEEDED|FAILED|CANCELLED|TIMED_OUT")) {
        return run;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted while awaiting terminal state for run " + runId);
      }
    }
    ReplayRun stuck = runRepository.findById(runId).orElse(null);
    fail(
        "run "
            + runId
            + " did not reach a terminal state within "
            + timeoutSeconds
            + "s (state="
            + (stuck != null ? stuck.getState() : "missing")
            + " phase="
            + (stuck != null ? stuck.getPhase() : "missing")
            + ")");
    return stuck; // unreachable; fail() throws
  }

  private ReplayRun requireRun(UUID runId) {
    return runRepository.findById(runId).orElseThrow(() -> new AssertionError("run missing"));
  }

  @Test
  void liveWorkerIdentityAndCapabilitiesPassProductionGates() {
    assertTrue(workerChecked);
    assertEquals(xrpsRepoHeadCommit, liveIdentity.xrspsBuildCommit());
    assertTrue(capabilityService.isCausalSimulationCertifiable());
    assertEquals(
        CapabilityManifest.Fidelity.XRSPS_AUTHORITATIVE, liveCapabilityManifest.fidelity());
  }

  @Test
  void productionManifestResetsThroughLiveKernel() throws Exception {
    UUID id = seedPublishedManifest();
    JsonNode document = manifestRepository.findById(id).orElseThrow().getManifestJson();
    XrspsKernelProcess kernel = new XrspsKernelProcess(properties.getXrsps(), mapper);
    try {
      kernel.start();
      var result = kernel.send("reset", mapper.createObjectNode().set("manifest", document));
      assertFalse(result.isError(), String.valueOf(result.error()));
      assertEquals(2, result.result().path("causalSample").path("projectionVersion").intValue());
      long tick = result.result().path("initialTick").longValue();
      for (int i = 0; i < 27; i++) {
        var action =
            mapper
                .createObjectNode()
                .put("type", "INTERACT_NPC")
                .put("targetStableId", "spot:" + i)
                .put("option", "net");
        var params =
            mapper
                .createObjectNode()
                .put("expectedTick", tick)
                .put("sourceActionIndex", i)
                .put("maxTicks", 10000);
        params.set("action", action);
        var step = kernel.send("step", params);
        assertFalse(step.isError(), "step " + i + ": " + step.error());
        tick = step.result().path("terminalTick").longValue();
      }
    } finally {
      kernel.close();
    }
  }

  @Test
  void productionParentAndFreshBranchPersistIdenticalCausalSuffixAndArtifacts() throws Exception {
    UUID manifestId = seedPublishedManifest();
    UUID parentId = createParentRun(manifestId).runId();
    ReplayRun parent = awaitTerminal(parentId, 90);
    assertEquals(ReplayRunState.SUCCEEDED, parent.getState(), parent.getTerminalReason());
    var parentSamples = hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(parentId);
    assertTrue(parentSamples.size() >= 29, "RESET, actions and terminal all persist");
    long ordinal = 0;
    for (StateHashSample sample : parentSamples) {
      assertEquals(2, sample.getProjectionVersion());
      assertEquals(ordinal++, sample.getTransitionOrdinal());
    }
    var refs = artifactRefRepository.findByRunIdAndState(parentId, ArtifactRef.STATE_FINALIZED);
    assertFalse(refs.isEmpty());
    var artifacts =
        new com.chromascape.web.replay.artifact.ReplayArtifactService(
            artifactRefRepository, properties);
    java.util.Set<Long> captures = new java.util.TreeSet<>();
    for (ArtifactRef ref : refs) {
      byte[] bytes = artifacts.readFinalized(ref);
      if (ref.getKind() == ArtifactKind.PROJECTION) {
        JsonNode projected = mapper.readTree(bytes);
        captures.add(projected.path("tick").longValue());
        assertEquals(2, projected.path("projectionVersion").intValue());
      }
    }
    assertTrue(captures.contains(0L), "reset projection captured");
    assertTrue(captures.stream().anyMatch(tick -> tick >= 100), "periodic projection captured");
    var checkpoints = checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(parentId, "main");
    assertTrue(checkpoints.size() >= 2);
    ReplayCheckpoint anchor = checkpoints.get(0);
    assertNotNull(anchor.getRestoreContext());
    assertNotNull(anchor.getChainArtifactChecksum());
    long start =
        mapper.readTree(anchor.getRestoreContext()).path("nextTransitionOrdinal").longValue();
    UUID childId = createBranchRun(parentId, anchor.getId(), "sc275-certified-child").runId();
    ReplayRun child = awaitTerminal(childId, 90);
    assertEquals(ReplayRunState.SUCCEEDED, child.getState(), child.getTerminalReason());
    var suffix =
        hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(childId).stream()
            .filter(sample -> sample.getTransitionOrdinal() >= start)
            .toList();
    var expected =
        parentSamples.stream().filter(sample -> sample.getTransitionOrdinal() >= start).toList();
    assertEquals(expected.size(), suffix.size());
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.get(i).getTransitionOrdinal(), suffix.get(i).getTransitionOrdinal());
      assertEquals(expected.get(i).getStateHash(), suffix.get(i).getStateHash());
      assertEquals(expected.get(i).getChainHash(), suffix.get(i).getChainHash());
    }
    var parentReport = reportRepository.findByRunId(parentId).orElseThrow();
    var childReport = reportRepository.findByRunId(childId).orElseThrow();
    assertEquals(parentReport.getFinalStateHash(), childReport.getFinalStateHash());
    assertEquals(parentReport.getFinalChainHash(), childReport.getFinalChainHash());
    assertEquals(2, childReport.getManifestVersion());
    assertEquals("XRSPS_AUTHORITATIVE", childReport.getFidelity());
    java.util.Set<String> parentFinalProjections = new java.util.HashSet<>();
    for (ArtifactRef ref : refs) {
      if (ref.getKind() == ArtifactKind.PROJECTION) {
        byte[] projectionBytes = artifacts.readFinalized(ref);
        JsonNode projected = mapper.readTree(projectionBytes);
        if (projected
                .path("domains")
                .path("restoreContext")
                .path("nextTransitionOrdinal")
                .longValue()
            == parentSamples.size()) {
          parentFinalProjections.add(java.util.Base64.getEncoder().encodeToString(projectionBytes));
        }
      }
    }
    assertFalse(parentFinalProjections.isEmpty());
    boolean comparedFinalProjection = false;
    for (ArtifactRef ref :
        artifactRefRepository.findByRunIdAndState(childId, ArtifactRef.STATE_FINALIZED)) {
      byte[] bytes = artifacts.readFinalized(ref);
      if (ref.getKind() == ArtifactKind.PROJECTION) {
        JsonNode projected = mapper.readTree(bytes);
        if (projected
                .path("domains")
                .path("restoreContext")
                .path("nextTransitionOrdinal")
                .longValue()
            == parentSamples.size()) {
          assertTrue(
              parentFinalProjections.contains(java.util.Base64.getEncoder().encodeToString(bytes)),
              "restored complete final projection matches uninterrupted execution");
          comparedFinalProjection = true;
        }
      }
    }
    assertTrue(comparedFinalProjection);
  }

  @Test
  void kernelTerminalStopsRemainingActionsAndPersistsFinalCheckpoint() {
    UUID parent = createParentRun(seedPublishedManifest(5)).runId();
    ReplayRun completed = awaitTerminal(parent, 90);
    assertEquals(ReplayRunState.SUCCEEDED, completed.getState(), completed.getTerminalReason());
    var samples = hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(parent);
    assertEquals(6, samples.size(), "RESET plus five actions, with the fifth terminal");
    assertEquals(5L, samples.get(samples.size() - 1).getId().getTick());
    assertEquals(
        1, checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(parent, "main").size());
    var parentReport = reportRepository.findByRunId(parent).orElseThrow();
    var terminalCheckpoint =
        checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(parent, "main").get(0);
    UUID child =
        createBranchRun(parent, terminalCheckpoint.getId(), "sc275-terminal-child").runId();
    ReplayRun restored = awaitTerminal(child, 90);
    assertEquals(ReplayRunState.SUCCEEDED, restored.getState(), restored.getTerminalReason());
    var childReport = reportRepository.findByRunId(child).orElseThrow();
    assertEquals(0, childReport.getExecutedActionCount());
    assertEquals(parentReport.getFinalStateHash(), childReport.getFinalStateHash());
    assertEquals(parentReport.getFinalChainHash(), childReport.getFinalChainHash());
    UUID retry =
        runService
            .retry(
                parent,
                new com.chromascape.web.replay.run.dto.RetryReplayRunRequest(
                    "sc275-retry-" + UUID.randomUUID()),
                "certification-owner")
            .runId();
    ReplayRun repeated = awaitTerminal(retry, 90);
    assertEquals(ReplayRunState.SUCCEEDED, repeated.getState(), repeated.getTerminalReason());
    var retryReport = reportRepository.findByRunId(retry).orElseThrow();
    assertEquals(2, retryReport.getManifestVersion());
    assertEquals("XRSPS_AUTHORITATIVE", retryReport.getFidelity());
    assertEquals(parentReport.getFinalStateHash(), retryReport.getFinalStateHash());
    assertEquals(parentReport.getFinalChainHash(), retryReport.getFinalChainHash());
  }

  @Test
  void storedCheckpointIdentitySubstitutionRejectsThroughRealWorker() throws Exception {
    UUID manifestId = seedPublishedManifest();
    UUID parentId = createParentRun(manifestId).runId();
    assertEquals(ReplayRunState.SUCCEEDED, awaitTerminal(parentId, 90).getState());
    ReplayCheckpoint anchor =
        checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(parentId, "main").get(0);
    ObjectNode changed = (ObjectNode) mapper.readTree(anchor.getRestoreContext());
    changed.put("snapshotDigest", "sha256:" + "ee".repeat(32));
    tx.execute(
        status -> {
          anchor.setRestoreContext(changed.toString());
          checkpointRepository.saveAndFlush(anchor);
          return null;
        });
    UUID child = createBranchRun(parentId, anchor.getId(), "sc275-forged-child").runId();
    ReplayRun failed = awaitTerminal(child, 90);
    assertEquals(ReplayRunState.FAILED, failed.getState());
    assertTrue(
        failed.getTerminalReason().contains("CHECKSUM_MISMATCH"), failed.getTerminalReason());
  }
}
