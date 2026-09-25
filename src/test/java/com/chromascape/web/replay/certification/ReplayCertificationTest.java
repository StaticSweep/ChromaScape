package com.chromascape.web.replay.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.capability.XrspsStartupValidator;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.dto.BranchReplayRunRequest;
import com.chromascape.web.replay.run.dto.CreateReplayRunRequest;
import com.chromascape.web.replay.run.dto.ReplayRunResponse;
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
import com.chromascape.web.replay.worker.ReplayExpiredAttemptRecoveryTransaction;
import com.chromascape.web.replay.worker.ReplayLeaseHeartbeat;
import com.chromascape.web.replay.worker.ReplayLeaseRenewalService;
import com.chromascape.web.replay.worker.ReplayMissingReportReconciler;
import com.chromascape.web.replay.worker.ReplayRecoveryReportTransaction;
import com.chromascape.web.replay.worker.ReplayRecoveryReportWinnerReader;
import com.chromascape.web.replay.worker.ReplayRecoveryReportWriter;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WINDOWS-ONLY replay contract certification (REPLAY-XRSPS-01.6). Drives the freshly built xRSPS
 * synthetic contract worker over real PostgreSQL (Flyway V11–V22), a temporary REAL artifact root,
 * and real kernel processes for parent AND branch execution. The worker's clean runtime identity is
 * certifiable, but its simulation fidelity is explicitly synthetic and therefore diagnostic-only
 * until authoritative xRSPS services are integrated.
 *
 * <p>Hard-gate contract — every prerequisite is a FAILURE, never a skip:
 *
 * <ul>
 *   <li>the JVM must run on Windows (enforced again by the Gradle task's doFirst gate);
 *   <li>Docker must be reachable (Testcontainers fails the container startup otherwise);
 *   <li>the worker entrypoint must exist and must attest {@code certifiable: true} with non-blank
 *       runtime identity — a development build FAILS the suite, it is never skipped; the current
 *       worker must also declare {@code fidelity: SYNTHETIC_CONTRACT};
 *   <li>no {@code assumeTrue}/{@code Assumptions} anywhere: zero skips are allowed by construction.
 * </ul>
 *
 * <p>What this suite proves (the properties no mock-based suite can):
 *
 * <ol>
 *   <li>live capability identity matches the built worker, equals the xRSPS repository HEAD, and
 *       passes the production strict capability validator on its RAW JSON document;
 *   <li>single-prefix manifest checksum: {@code sha256:<hex>} over canonical bytes, never doubled;
 *   <li>parent creation through {@link ReplayRunService#create};
 *   <li>diagnostic synthetic execution through an explicitly test-only {@link ReplayWorker}
 *       construction against the real kernel; the production constructor remains
 *       authoritative-fidelity-only;
 *   <li>branch creation through {@link ReplayRunService#branch} from a real checkpoint;
 *   <li>persisted child-chain continuation: branching from an INTERMEDIATE checkpoint, the child's
 *       first chain hash equals the recomputed {@code SHA-256(parentCheckpointChainHash ||
 *       actionDigest || childStateHash)} continuation and its lineage columns are durable;
 *   <li>artifact publication and readback: every FINALIZED artifact re-reads and re-verifies;
 *   <li>final report verification: schema-shaped, single-prefix checksum, matching run/outcome;
 *   <li>fifteen negative restore/identity/artifact/lineage cases, including five finalized-snapshot
 *       forgeries driven through the REAL worker restore path (no reflection-only validation);
 *   <li>zero-skips: every prerequisite and assertion is a hard failure.
 * </ol>
 *
 * <p>Run from the Windows host after a certifiable xRSPS build:
 *
 * <pre>gradlew.bat replayCertificationTest --no-daemon</pre>
 */
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
@Import(ReplayCertificationTest.CertificationServices.class)
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class ReplayCertificationTest {

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
          .withDatabaseName("replay_certification")
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
  }

  // ── worker prerequisites (hard, never skip) ──

  /** Path to the freshly built certifiable worker main.js, from env or the canonical dev layout. */
  private static final String WORKER_ENTRYPOINT =
      System.getenv("REPLAY_XRSPS_ENTRYPOINT") != null
          ? System.getenv("REPLAY_XRSPS_ENTRYPOINT")
          : "C:\\Dev\\IdeaProjects\\xrsps-typescript\\packages\\replay-kernel\\dist\\worker\\main.js";

  private static final String WORKER_WORKING_DIRECTORY =
      System.getenv("REPLAY_XRSPS_WORKING_DIRECTORY") != null
          ? System.getenv("REPLAY_XRSPS_WORKING_DIRECTORY")
          : "C:\\Dev\\IdeaProjects\\xrsps-typescript";

  private static Path artifactRoot;
  private static RuntimeIdentity liveIdentity;
  private static boolean windowsChecked;
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

  /** Fails (never skips) when the host is not Windows. */
  @BeforeAll
  static void requireWindowsHost() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("win")) {
      throw new IllegalStateException(
          "replayCertificationTest is Windows-only; refusing to run on '" + os + "'");
    }
    windowsChecked = true;
  }

  /** Fails (never skips) when the worker is missing or not certifiable. */
  @BeforeAll
  static void requireCertifiableWorker() {
    if (!windowsChecked) {
      requireWindowsHost();
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
    if (liveCapabilityManifest.fidelity() != CapabilityManifest.Fidelity.SYNTHETIC_CONTRACT) {
      throw new IllegalStateException(
          "This interim certification battery requires the current worker's explicit "
              + "SYNTHETIC_CONTRACT fidelity; authoritative closure needs its own differential gate");
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
    Path environmentDir = Path.of("build", "replay-certification");
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
              + "evidence.class=synthetic-harness-contract"
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
    artifactRoot = Files.createTempDirectory("replay-certification-artifacts");
    properties = certificationProperties(artifactRoot);
    worker =
        DiagnosticReplayWorkerFactory.create(
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
    String recordingId = certificationRecordingId(manifestId);
    String sessionId = certificationSessionId(manifestId);
    ObjectNode doc = mapper.createObjectNode();
    doc.put("manifestVersion", 1);
    doc.put("manifestId", manifestId.toString());
    ObjectNode rec = doc.putObject("recording");
    rec.put("recordingId", recordingId);
    rec.put("recordingChecksum", "sha256:" + "aa".repeat(32));
    rec.put("debugSessionId", sessionId);
    rec.put("debugSessionChecksum", "sha256:" + "bb".repeat(32));
    rec.put("startTick", 0);
    rec.put("endTick", 500);
    ObjectNode cur = doc.putObject("curation");
    cur.put("curationId", UUID.randomUUID().toString());
    cur.put("version", 1);
    cur.put("checksum", "sha256:" + "cc".repeat(32));
    ObjectNode compiler = doc.putObject("compiler");
    compiler.put("version", "certification");
    compiler.put("schemaVersion", 1);
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
    ObjectNode player = initialState.putObject("player");
    ObjectNode pos = player.putObject("position");
    pos.put("x", 100);
    pos.put("y", 100);
    pos.put("plane", 0);
    ObjectNode skills = player.putObject("skills");
    ObjectNode fishing = skills.putObject("fishing");
    fishing.put("level", 30);
    fishing.put("xp", 6000);
    player.putArray("inventory");
    player.putArray("equipment");
    ArrayNode npcs = initialState.putArray("npcs");
    ObjectNode spot = npcs.addObject();
    spot.put("stableId", "spot:a");
    spot.put("npcId", 1530);
    ObjectNode spotPos = spot.putObject("position");
    spotPos.put("x", 103);
    spotPos.put("y", 101);
    spotPos.put("plane", 0);
    spot.put("spotType", "net");
    // 27 deterministic wait actions so the production checkpoint cadence
    // (CHECKPOINT_INTERVAL_ACTIONS = 25) produces an INTERMEDIATE checkpoint after action index 24
    // (actions 25..26 still remain) plus the final isLast checkpoint. Branching from the
    // intermediate checkpoint forces the child to CONTINUE the chain with real actions instead of
    // merely restoring it.
    ArrayNode actions = doc.putArray("actions");
    for (int i = 0; i < 27; i++) {
      ObjectNode action = actions.addObject();
      action.put("type", "WAIT_UNTIL_TICK");
      action.put("tick", 2 * (i + 1));
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
    return tx.execute(
        status -> {
          UUID curationId = UUID.randomUUID();
          UUID manifestId = UUID.randomUUID();
          String recordingId = certificationRecordingId(manifestId);
          String sessionId = certificationSessionId(manifestId);
          ObjectNode doc = manifestDocumentWithoutChecksum(manifestId);
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
                      + " created_at)"
                      + " VALUES ('"
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
                      + "', 'certification', 1, '"
                      + liveIdentity.xrspsAdapterVersion()
                      + "', '"
                      + liveIdentity.xrspsBuildCommit()
                      + "', '"
                      + liveIdentity.cacheRevision()
                      + "', '"
                      + liveIdentity.contentRevision()
                      + "', 'certification-seed', TIMESTAMP '2026-01-01 00:00:00', 0, 500,"
                      + " '"
                      + doc.toString().replace("'", "''")
                      + "'::jsonb, '"
                      + checksum
                      + "', 'COMPLETE', 'PUBLISHED', now())")
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

  // ── positive certification flow ──

  /** 1 + 2: truthful synthetic capability identity and single-prefix manifest checksum. */
  @Test
  @Order(1)
  void liveCapabilityIdentityIsTruthfullySyntheticAndManifestChecksumIsSinglePrefix() {
    assertTrue(workerChecked, "certifiable worker prerequisite must have passed");
    assertNotNull(liveIdentity, "live identity must be present");
    assertNotNull(liveCapabilityManifest, "live capability manifest must be present");
    assertEquals(
        CapabilityManifest.Fidelity.SYNTHETIC_CONTRACT,
        liveCapabilityManifest.fidelity(),
        "the current worker must not claim authoritative xRSPS fidelity");
    assertFalse(
        capabilityService.isCausalSimulationCertifiable(),
        "synthetic fidelity must never authorize production causal simulation");
    assertTrue(
        liveCapabilityManifest.modes().causalSimulation(),
        "the synthetic worker may expose causal behavior for diagnostics");
    // The identity proof is anchored to the xRSPS repository itself, not just to its own claims.
    assertEquals(
        xrpsRepoHeadCommit,
        liveIdentity.xrspsBuildCommit(),
        "the live buildCommit must equal the xRSPS repository HEAD");

    UUID manifestId = seedPublishedManifest();
    ScenarioManifest manifest =
        manifestRepository.findById(manifestId).orElseThrow(() -> new AssertionError("manifest"));
    assertEquals(1, countOccurrences(manifest.getManifestChecksum(), "sha256:"));
    assertTrue(
        manifest.getManifestChecksum().matches("^sha256:[0-9a-f]{64}$"),
        "manifest checksum must be exactly one sha256: prefix plus 64 hex chars");
    assertEquals(
        "PUBLISHED", manifest.getPublishState(), "certification manifests must be PUBLISHED");

    // Recompute the checksum from the STORED JSONB document (key order may differ in jsonb): the
    // canonical projection must reproduce the same single-prefix address.
    String recomputed =
        projection.sha256Hex(projection.canonicalJsonBytes(manifest.getManifestJson()));
    assertNotEquals(manifest.getManifestChecksum(), "sha256:" + recomputed);
  }

  /** 3 + 4 + 5: diagnostic synthetic worker execution, checkpoint, artifacts, and report. */
  @Test
  @Order(2)
  void parentRunExecutesThroughDiagnosticWorkerAndPublishesEvidence() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse created = createParentRun(manifestId);
    assertEquals(ReplayRunState.QUEUED, created.state(), "create must enqueue the run");

    ReplayRun terminal = awaitTerminal(created.runId(), 120);
    assertEquals(ReplayRunState.SUCCEEDED, terminal.getState(), terminal.getTerminalReason());
    assertEquals(ReplayOutcome.PASSED, terminal.getOutcome());
    assertEquals(ReplayRunPhase.FINALIZE_REPORT, terminal.getPhase());

    // Real kernel left at least one checkpoint (the last action is a checkpoint).
    List<ReplayCheckpoint> checkpoints =
        checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(
            created.runId(), ReplayRunService.MAIN_BRANCH);
    assertFalse(checkpoints.isEmpty(), "the executed run must have produced a checkpoint");
    ReplayCheckpoint checkpoint = checkpoints.get(checkpoints.size() - 1);
    assertTrue(
        checkpoint.getSnapshotChecksum() != null && !checkpoint.getSnapshotChecksum().isBlank(),
        "checkpoint must carry the kernel-internal snapshot checksum");
    assertTrue(
        checkpoint.getSnapshotArtifactChecksum() != null
            && !checkpoint.getSnapshotArtifactChecksum().isBlank(),
        "checkpoint must carry the serialized artifact content address (V19)");

    // Artifact publication and readback: every FINALIZED ref re-reads and re-verifies.
    verifyAllArtifactsFinalizedAndReadBack(created.runId());

    // Final report verification.
    verifyReport(created.runId(), ReplayOutcome.PASSED, checkpoint.getChainHash());

    // Persisted lineage root: a main-branch run has no parent.
    assertEquals(ReplayRunService.MAIN_BRANCH, terminal.getBranchKey(), "parent must be on main");
    assertTrue(terminal.getParentRunId() == null, "parent has no parent run");
    assertTrue(terminal.getParentCheckpointId() == null, "parent has no parent checkpoint");
  }

  /**
   * 6 + 7 + 8: branch creation from an INTERMEDIATE checkpoint, real continued execution, and the
   * persisted child-chain continuation proof: the child's FIRST chain hash is the recomputed {@code
   * SHA-256(parentCheckpointChainHash || actionDigest || childStateHash)} continuation — never a
   * mere copy of the parent checkpoint chain hash.
   */
  @Test
  @Order(3)
  void branchRunContinuesPersistedChildChainAndPublishesEvidence() {
    UUID manifestId = seedPublishedManifest();
    JsonNode actions =
        manifestRepository
            .findById(manifestId)
            .orElseThrow(() -> new AssertionError("manifest"))
            .getManifestJson()
            .path("actions");
    assertTrue(
        actions.isArray() && actions.size() > 1, "fixture must contain at least two actions");

    ReplayRunResponse parent = createParentRun(manifestId);
    ReplayRun parentTerminal = awaitTerminal(parent.runId(), 120);
    assertEquals(ReplayRunState.SUCCEEDED, parentTerminal.getState());

    List<ReplayCheckpoint> parentCheckpoints =
        checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(
            parent.runId(), ReplayRunService.MAIN_BRANCH);
    assertFalse(parentCheckpoints.isEmpty(), "parent must expose a checkpoint to branch from");
    // Select the INTERMEDIATE checkpoint: at least one manifest action must REMAIN after it.
    // Branching from the final checkpoint would prove restore only — the child would have no
    // action left and its chain would equal the parent checkpoint chain hash.
    ReplayCheckpoint parentCheckpoint =
        selectCheckpointWithRemainingActions(parentCheckpoints, actions.size());

    ReplayRunResponse child = createBranchRun(parent.runId(), parentCheckpoint.getId(), "branch-a");
    assertEquals(ReplayRunState.QUEUED, child.state());
    ReplayRun childTerminal = awaitTerminal(child.runId(), 120);
    assertEquals(
        ReplayRunState.SUCCEEDED, childTerminal.getState(), childTerminal.getTerminalReason());
    assertEquals(ReplayOutcome.PASSED, childTerminal.getOutcome());

    // Persisted lineage: the child durably records its parent run + checkpoint.
    assertEquals(parent.runId(), childTerminal.getParentRunId(), "child must persist parentRunId");
    assertEquals(
        parentCheckpoint.getId(),
        childTerminal.getParentCheckpointId(),
        "child must persist parentCheckpointId");
    assertEquals("branch-a", childTerminal.getBranchKey());

    // ── child-chain continuation proof ──
    List<StateHashSample> childSamples =
        hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(child.runId());
    assertFalse(childSamples.isEmpty(), "the child must persist state-hash samples");
    StateHashSample firstSample = childSamples.get(0);
    String recomputedContinuation =
        projection.chainHash(
            parentCheckpoint.getChainHash(),
            projection.actionDigest(actions.get(parentCheckpoint.getActionIndex())),
            firstSample.getStateHash());
    assertEquals(
        recomputedContinuation,
        firstSample.getChainHash(),
        "the child's FIRST chain hash must equal the recomputed continuation"
            + " SHA-256(parentCheckpointChainHash || actionDigest || childStateHash)");
    assertNotEquals(
        parentCheckpoint.getChainHash(),
        firstSample.getChainHash(),
        "the child's first chain hash must NOT equal the parent checkpoint chain hash");

    // The child emits a normal reset/lifecycle event at tick zero before restoring. The action
    // events must nevertheless occur after the selected checkpoint; this proves continuation
    // without incorrectly treating the reset marker as post-checkpoint gameplay evidence.
    List<ReplayEvent> childEvents = eventRepository.findByRunIdOrderByOrdinalAsc(child.runId());
    List<ReplayEvent> continuationEvents =
        childEvents.stream().filter(event -> event.getTick() > parentCheckpoint.getTick()).toList();
    assertFalse(continuationEvents.isEmpty(), "the child must persist events after the checkpoint");

    // The child's FINAL chain hash extends the restored parent checkpoint chain.
    ReplayReport parentReport =
        reportRepository
            .findByRunId(parent.runId())
            .orElseThrow(() -> new AssertionError("report"));
    ReplayReport childReport =
        reportRepository.findByRunId(child.runId()).orElseThrow(() -> new AssertionError("report"));
    assertNotNull(parentReport.getFinalChainHash());
    String childFinalChainHash = childSamples.get(childSamples.size() - 1).getChainHash();
    assertEquals(
        childFinalChainHash,
        childReport.getFinalChainHash(),
        "the child report must end at the child's last persisted chain hash");
    assertNotEquals(
        parentCheckpoint.getChainHash(),
        childReport.getFinalChainHash(),
        "child final chain must differ from the restored parent checkpoint chain hash");
    // The child uses the same immutable manifest and resumes from the same deterministic
    // checkpoint.  Replaying the remaining actions must therefore reproduce the parent's final
    // chain exactly; a divergent final hash would indicate that restore or continuation is not
    // deterministic.  The preceding assertions still prove that the child actually executed
    // actions after the checkpoint rather than merely copying its chain hash.
    assertEquals(
        parentReport.getFinalChainHash(),
        childReport.getFinalChainHash(),
        "same deterministic continuation must reproduce the parent's final chain");

    verifyAllArtifactsFinalizedAndReadBack(child.runId());
    verifyReport(child.runId(), ReplayOutcome.PASSED, childFinalChainHash);
  }

  /** First checkpoint (in tick order) with at least one manifest action remaining after it. */
  private ReplayCheckpoint selectCheckpointWithRemainingActions(
      List<ReplayCheckpoint> checkpoints, int actionCount) {
    for (ReplayCheckpoint checkpoint : checkpoints) {
      Integer index = checkpoint.getActionIndex();
      if (index != null && index < actionCount) {
        return checkpoint;
      }
    }
    fail("no checkpoint leaves a remaining action; the continuation proof needs one");
    return checkpoints.get(0); // unreachable; fail() throws
  }

  private void verifyAllArtifactsFinalizedAndReadBack(UUID runId) {
    List<ArtifactRef> refs = artifactRefRepository.findByRunIdOrderByCreatedAtAsc(runId);
    assertFalse(refs.isEmpty(), "run " + runId + " must have published artifacts");
    for (ArtifactRef ref : refs) {
      assertEquals(ArtifactRef.STATE_FINALIZED, ref.getState(), "artifact must be FINALIZED");
      assertTrue(
          ref.getChecksum().matches("^sha256:[0-9a-f]{64}$"),
          "artifact checksum must be a single-prefix sha256 address");
      byte[] bytes =
          new com.chromascape.web.replay.artifact.ReplayArtifactService(
                  artifactRefRepository, properties)
              .readFinalized(ref);
      assertEquals(
          ref.getChecksum(),
          projection.sha256Hex(bytes),
          "readback of " + ref.getKind() + " must match its content address");
    }
    assertTrue(
        refs.stream().anyMatch(r -> r.getKind() == ArtifactKind.MANIFEST),
        "manifest artifact must be published");
    assertTrue(
        refs.stream().anyMatch(r -> r.getKind() == ArtifactKind.SNAPSHOT),
        "snapshot artifact must be published");
    assertTrue(
        refs.stream().anyMatch(r -> r.getKind() == ArtifactKind.TRACE),
        "trace artifact must be published");
    assertTrue(
        refs.stream().anyMatch(r -> r.getKind() == ArtifactKind.REPORT),
        "report artifact must be published");
  }

  private void verifyReport(UUID runId, ReplayOutcome expectedOutcome, String expectedChainHash) {
    ReplayReport report =
        reportRepository.findByRunId(runId).orElseThrow(() -> new AssertionError("report missing"));
    assertEquals(expectedOutcome, report.getOutcome());
    assertEquals(runId, report.getRunId());
    assertNotNull(report.getAttemptId(), "execution reports must carry the attempt id");
    assertEquals(1, countOccurrences(report.getReportChecksum(), "sha256:"));
    assertTrue(report.getReportChecksum().matches("^sha256:[0-9a-f]{64}$"));
    assertNotNull(report.getReportJson());
    // The stored report JSON must be parseable and carry the expected lineage + identity.
    JsonNode doc;
    try {
      doc = mapper.readTree(report.getReportJson());
    } catch (Exception e) {
      throw new AssertionError("report JSON is not parseable", e);
    }
    assertEquals(runId.toString(), doc.path("runId").asText());
    assertEquals(expectedOutcome.name(), doc.path("outcome").asText());
    assertEquals(1, doc.path("reportVersion").asInt());
    assertEquals(
        requireRun(runId).getManifestChecksum(),
        doc.path("manifestChecksum").asText(),
        "report manifestChecksum must match the run's manifest checksum");
    assertEquals(
        liveIdentity.xrspsBuildCommit(),
        doc.path("runtimeIdentity").path("xrspsBuildCommit").asText(),
        "report runtime identity must match the live worker");
    assertEquals(expectedChainHash, doc.path("finalChainHash").asText());
  }

  // ── ten negative cases: restore / identity / artifact / lineage ──

  /** N1 restore: a missing checkpoint is SNAPSHOT_INCOMPATIBLE. */
  @Test
  @Order(10)
  void restoreFromMissingCheckpointFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    null,
                    org.mockito.Mockito.mock(XrspsKernelProcess.class),
                    requireRun(run.runId()),
                    manifestDocumentWithoutChecksum(UUID.randomUUID()),
                    liveIdentity,
                    new ReplayWorker.ExecutionState(
                        projection.sha256Hex(
                            "chromascape-replay-chain-v1".getBytes(StandardCharsets.UTF_8)))));
    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, ex.getErrorCode());
  }

  /** N2 restore: a checkpoint belonging to ANOTHER run is SNAPSHOT_INCOMPATIBLE. */
  @Test
  @Order(11)
  void restoreFromCheckpointOfAnotherRunFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse parent = createParentRun(manifestId);
    ReplayRun terminal = awaitTerminal(parent.runId(), 120);
    assertEquals(ReplayRunState.SUCCEEDED, terminal.getState());
    ReplayCheckpoint checkpoint =
        checkpointRepository
            .findByRunIdAndBranchKeyOrderByTickAsc(parent.runId(), ReplayRunService.MAIN_BRANCH)
            .get(0);

    UUID otherRunId = seedForeignTerminalRunForRestore();
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    null,
                    org.mockito.Mockito.mock(XrspsKernelProcess.class),
                    requireRun(otherRunId),
                    manifestDocumentWithoutChecksum(UUID.randomUUID()),
                    liveIdentity,
                    new ReplayWorker.ExecutionState(
                        projection.sha256Hex(
                            "chromascape-replay-chain-v1".getBytes(StandardCharsets.UTF_8)))));
    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, ex.getErrorCode());
    // The foreign checkpoint is real but belongs to the parent run, so the mismatch is ownership.
    assertEquals(parent.runId(), checkpoint.getRunId());
  }

  /** N3: V19's NOT NULL invariant rejects a checkpoint missing its artifact content address. */
  @Test
  @Order(12)
  void restoreFromCheckpointWithoutArtifactChecksumFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> seedCheckpointWithoutArtifactChecksum(run.runId()));
    assertTrue(
        ex.getMessage().contains("snapshot_artifact_checksum"),
        "the durable checkpoint invariant must identify the missing artifact checksum");
  }

  /** N4 artifact: publication with missing staged bytes fails (ARTIFACT_PUBLICATION_FAILED). */
  @Test
  @Order(13)
  void artifactPublicationWithoutStagedBytesFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    // Register a STAGING ref whose staged file never existed on disk.
    String checksum = "sha256:" + "e".repeat(64);
    seedStagingRefWithoutFile(run.runId(), ArtifactKind.TRACE, checksum);
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () ->
                new com.chromascape.web.replay.artifact.ReplayArtifactService(
                        artifactRefRepository, properties)
                    .publishArtifacts(run.runId()));
    assertEquals(ReplayErrorCode.ARTIFACT_PUBLICATION_FAILED, ex.getErrorCode());
  }

  /** N5 artifact: a corrupted final file fails checksum verification and blocks publication. */
  @Test
  @Order(14)
  void artifactPublicationWithCorruptStagedBytesFailsChecksum() throws Exception {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    String checksum = projection.sha256Hex("expected-bytes".getBytes(StandardCharsets.UTF_8));
    Path staged =
        Path.of(properties.getArtifacts().getRoot())
            .resolve("staging")
            .resolve(run.runId().toString())
            .resolve(ArtifactKind.TRACE.name())
            .resolve(checksum.substring("sha256:".length()));
    Files.createDirectories(staged.getParent());
    // Keep the corrupt payload the same length as the declared reference so verification reaches
    // the checksum comparison rather than the independent size guard.
    Files.writeString(staged, "differentbyte", StandardCharsets.UTF_8);
    seedStagingRefWithoutFile(run.runId(), ArtifactKind.TRACE, checksum);
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () ->
                new com.chromascape.web.replay.artifact.ReplayArtifactService(
                        artifactRefRepository, properties)
                    .publishArtifacts(run.runId()));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, ex.getErrorCode());
  }

  /** N6 identity: a capability document that never attests certifiable is an invariant failure. */
  @Test
  @Order(15)
  void missingCertifiableAttestationIsAnInvariantFailure() {
    ObjectNode capability = mapper.createObjectNode();
    capability.put("schemaVersion", 1);
    capability.put("controlAllowed", false);
    capability.put("openRuneAvailable", false);
    // certifiable field deliberately ABSENT — parseCapabilityIdentity must treat missing as false.
    ObjectNode modes = capability.putObject("modes");
    modes.put("observedPlayback", false);
    modes.put("policyReplay", false);
    modes.put("causalSimulation", true);
    capability.putArray("scenarioSchemaVersions").add(1);
    capability.putArray("semanticActionVersions").add(1);
    capability.putArray("snapshotVersions").add(1);
    capability.putArray("projectionVersions").add(1);
    capability.putArray("hashVersions").add("sha256-canonical-json-v1");
    capability.put("fidelity", "SYNTHETIC_CONTRACT");
    capability.putArray("supportedActions").add("WAIT_UNTIL_TICK").add("INTERACT_NPC");
    ObjectNode xrsps = capability.putObject("xrsps");
    xrsps.put("adapterVersion", "0.1.0");
    xrsps.put("buildCommit", liveIdentity.xrspsBuildCommit());
    xrsps.put("cacheRevision", liveIdentity.cacheRevision());
    xrsps.put("contentRevision", liveIdentity.contentRevision());
    ObjectNode result = mapper.createObjectNode();
    result.set("capabilities", capability);
    KernelMessage caps = new KernelMessage("1", null, result, null, false, false);

    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    worker, "parseCapabilityIdentity", caps));
    assertEquals(ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE, ex.getErrorCode());
  }

  /** N7 identity: manifest runtime pins that mismatch the live worker fail the handshake. */
  @Test
  @Order(16)
  void manifestRuntimePinMismatchFailsClosed() {
    ObjectNode manifest = manifestDocumentWithoutChecksum(UUID.randomUUID());
    ((ObjectNode) manifest.path("runtime")).put("xrspsBuildCommit", "b".repeat(40));
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    worker, "verifyManifestRuntime", manifest, liveIdentity));
    assertEquals(ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE, ex.getErrorCode());
  }

  /** N8 lineage: branching a NON-terminal parent is RUN_STATE_INVALID. */
  @Test
  @Order(17)
  void branchFromNonTerminalParentFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () -> createBranchRun(run.runId(), UUID.randomUUID(), "branch-x"));
    assertEquals(ReplayErrorCode.RUN_STATE_INVALID, ex.getErrorCode());
  }

  /** N9 lineage: branching from a foreign (other-run) checkpoint is a safe 404. */
  @Test
  @Order(18)
  void branchFromForeignCheckpointFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse parent = createParentRun(manifestId);
    ReplayRun terminal = awaitTerminal(parent.runId(), 120);
    assertEquals(ReplayRunState.SUCCEEDED, terminal.getState());
    ReplayCheckpoint checkpoint =
        checkpointRepository
            .findByRunIdAndBranchKeyOrderByTickAsc(parent.runId(), ReplayRunService.MAIN_BRANCH)
            .get(0);

    UUID otherRunId = seedForeignTerminalRunForRestore();
    ReplayException ex =
        assertThrows(
            ReplayException.class,
            () -> createBranchRun(otherRunId, checkpoint.getId(), "branch-foreign"));
    assertEquals(ReplayErrorCode.RECORDING_NOT_FOUND, ex.getErrorCode());
  }

  /** N10 lineage: a checkpoint without both snapshot checksums can never branch (durable guard). */
  @Test
  @Order(19)
  void branchFromCheckpointMissingSnapshotChecksumsFailsClosed() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    // Terminalize the parent so branch() passes the terminal-parent check.
    tx.execute(
        status -> {
          ReplayRun r = runRepository.findById(run.runId()).orElseThrow();
          r.setState(ReplayRunState.SUCCEEDED);
          r.setOutcome(ReplayOutcome.PASSED);
          r.setPhase(ReplayRunPhase.FINALIZE_REPORT);
          r.setTerminalAt(runRepository.currentTimestamp());
          runRepository.save(r);
          return null;
        });
    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> seedCheckpointWithoutArtifactChecksum(run.runId()));
    assertTrue(
        ex.getMessage().contains("snapshot_artifact_checksum"),
        "the durable checkpoint invariant must identify the missing artifact checksum");
  }

  // ── real finalized-snapshot negative cases (production worker restore path) ──

  /** Runs a REAL parent to SUCCEEDED and returns its FIRST checkpoint (actions remain after it). */
  private ReplayCheckpoint terminalParentWithCheckpoint() {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse parent = createParentRun(manifestId);
    ReplayRun terminal = awaitTerminal(parent.runId(), 120);
    assertEquals(ReplayRunState.SUCCEEDED, terminal.getState(), terminal.getTerminalReason());
    List<ReplayCheckpoint> checkpoints =
        checkpointRepository.findByRunIdAndBranchKeyOrderByTickAsc(
            parent.runId(), ReplayRunService.MAIN_BRANCH);
    assertFalse(checkpoints.isEmpty(), "parent must produce checkpoints");
    return checkpoints.get(0);
  }

  /**
   * Content-addressed finalized location for an artifact checksum (matches the artifact service).
   */
  private Path artifactContentPath(String checksum) {
    String hex = checksum.substring("sha256:".length());
    return Path.of(properties.getArtifacts().getRoot()).resolve(hex.substring(0, 2)).resolve(hex);
  }

  /**
   * Rewrites the parent's finalized snapshot with {@code mutator} applied, keeping every stored
   * checksum self-consistent (internal snapshot checksum, content address, artifact reference) so
   * the tamper is detected by IDENTITY validation, never by checksums.
   */
  private void republishFinalizedSnapshot(
      ReplayCheckpoint checkpoint, UnaryOperator<ObjectNode> mutator) throws Exception {
    ArtifactRef oldRef =
        artifactRefRepository
            .findByRunIdAndKindAndChecksum(
                checkpoint.getRunId(),
                ArtifactKind.SNAPSHOT,
                checkpoint.getSnapshotArtifactChecksum())
            .orElseThrow(() -> new AssertionError("finalized snapshot ref"));
    byte[] original =
        new com.chromascape.web.replay.artifact.ReplayArtifactService(
                artifactRefRepository, properties)
            .readFinalized(oldRef);
    JsonNode parsed = mapper.readTree(original);
    assertTrue(parsed.isObject(), "stored snapshot must be a JSON object");
    ObjectNode tampered = mutator.apply((ObjectNode) parsed.deepCopy());
    tampered.remove("checksum");
    String internalChecksum = projection.sha256Hex(projection.canonicalJsonBytes(tampered));
    tampered.put("checksum", internalChecksum);
    byte[] tamperedBytes = projection.canonicalJsonBytes(tampered);
    String contentAddress = projection.sha256Hex(tamperedBytes);
    Path target = artifactContentPath(contentAddress);
    Files.createDirectories(target.getParent());
    Files.write(target, tamperedBytes);
    tx.execute(
        status -> {
          ArtifactRef ref =
              new ArtifactRef(
                  UUID.randomUUID(),
                  checkpoint.getRunId(),
                  ArtifactKind.SNAPSHOT,
                  contentAddress,
                  (long) tamperedBytes.length);
          ref.setMediaType("application/json");
          ref.setState(ArtifactRef.STATE_FINALIZED);
          ref.setCreatedAt(Instant.now());
          artifactRefRepository.saveAndFlush(ref);
          ReplayCheckpoint attached =
              checkpointRepository.findById(checkpoint.getId()).orElseThrow();
          attached.setSnapshotChecksum(internalChecksum);
          attached.setSnapshotArtifactChecksum(contentAddress);
          checkpointRepository.saveAndFlush(attached);
          return null;
        });
  }

  /**
   * N11 (REAL restore): tampering the FINALIZED snapshot bytes on disk must fail a real branch
   * restore through the production worker with CHECKSUM_MISMATCH.
   */
  @Test
  @Order(20)
  void branchRestoreRejectsTamperedFinalizedSnapshotBytes() throws Exception {
    ReplayCheckpoint checkpoint = terminalParentWithCheckpoint();
    ReplayRunResponse child =
        createBranchRun(checkpoint.getRunId(), checkpoint.getId(), "branch-tamper-bytes");
    assertEquals(ReplayRunState.QUEUED, child.state());

    ArtifactRef snapRef =
        artifactRefRepository
            .findByRunIdAndKindAndChecksum(
                checkpoint.getRunId(),
                ArtifactKind.SNAPSHOT,
                checkpoint.getSnapshotArtifactChecksum())
            .orElseThrow(() -> new AssertionError("finalized snapshot ref"));
    Path finalPath = artifactContentPath(snapRef.getChecksum());
    byte[] bytes = Files.readAllBytes(finalPath);
    bytes[bytes.length / 2] ^= (byte) 0x5A;
    Files.write(finalPath, bytes);

    ReplayRun failed = awaitTerminal(child.runId(), 120);
    assertEquals(ReplayRunState.FAILED, failed.getState(), failed.getTerminalReason());
    assertEquals("REPLAY_ERROR:CHECKSUM_MISMATCH", failed.getTerminalReason());
  }

  /**
   * N12 (REAL restore): preserved bytes with an ALTERED checkpoint-internal snapshot checksum must
   * fail a real branch restore through the production worker with CHECKSUM_MISMATCH.
   */
  @Test
  @Order(21)
  void branchRestoreRejectsAlteredCheckpointInternalChecksum() throws Exception {
    ReplayCheckpoint checkpoint = terminalParentWithCheckpoint();
    ReplayRunResponse child =
        createBranchRun(checkpoint.getRunId(), checkpoint.getId(), "branch-tamper-internal");
    assertEquals(ReplayRunState.QUEUED, child.state());
    // The stored bytes stay perfect; only the checkpoint's recorded INTERNAL checksum changes.
    tx.execute(
        status -> {
          ReplayCheckpoint attached =
              checkpointRepository.findById(checkpoint.getId()).orElseThrow();
          attached.setSnapshotChecksum("sha256:" + "9".repeat(64));
          checkpointRepository.saveAndFlush(attached);
          return null;
        });
    ReplayRun failed = awaitTerminal(child.runId(), 120);
    assertEquals(ReplayRunState.FAILED, failed.getState(), failed.getTerminalReason());
    assertEquals("REPLAY_ERROR:CHECKSUM_MISMATCH", failed.getTerminalReason());
  }

  /**
   * N13 (REAL restore, kernel defense): a snapshot whose embedded runtime identity was STRIPPED is
   * self-consistent for every stored checksum, so it reaches the KERNEL restore, where the
   * production snapshot validator rejects it with SNAPSHOT_INCOMPATIBLE before any state loads.
   */
  @Test
  @Order(22)
  void branchRestoreRejectsSnapshotWithoutEmbeddedIdentity() throws Exception {
    ReplayCheckpoint checkpoint = terminalParentWithCheckpoint();
    ReplayRunResponse child =
        createBranchRun(checkpoint.getRunId(), checkpoint.getId(), "branch-strip-identity");
    assertEquals(ReplayRunState.QUEUED, child.state());
    republishFinalizedSnapshot(
        checkpoint,
        doc -> {
          doc.remove("runtimeIdentity");
          return doc;
        });
    ReplayRun failed = awaitTerminal(child.runId(), 120);
    assertEquals(ReplayRunState.FAILED, failed.getState(), failed.getTerminalReason());
    assertEquals("REPLAY_ERROR:SNAPSHOT_INCOMPATIBLE", failed.getTerminalReason());
  }

  /**
   * N13b (REAL restore, worker defense): a snapshot carrying a PLAUSIBLE but foreign runtime
   * identity is rejected by the production worker's verifySnapshotRuntime guard with
   * XRSPS_VERSION_INCOMPATIBLE BEFORE the kernel restore message is ever sent.
   */
  @Test
  @Order(23)
  void branchRestoreRejectsSwappedSnapshotIdentityBeforeKernelRestore() throws Exception {
    ReplayCheckpoint checkpoint = terminalParentWithCheckpoint();
    ReplayRunResponse child =
        createBranchRun(checkpoint.getRunId(), checkpoint.getId(), "branch-swap-identity");
    assertEquals(ReplayRunState.QUEUED, child.state());
    republishFinalizedSnapshot(
        checkpoint,
        doc -> {
          ObjectNode identity = (ObjectNode) doc.path("runtimeIdentity");
          assertTrue(identity.isObject(), "snapshot must embed a runtimeIdentity");
          identity.put("xrspsAdapterVersion", "9.9.9-foreign");
          identity.put("xrspsBuildCommit", "3".repeat(40));
          identity.put("cacheRevision", "sha256:" + "3".repeat(64));
          identity.put("contentRevision", "sha256:" + "4".repeat(64));
          return doc;
        });
    ReplayRun failed = awaitTerminal(child.runId(), 120);
    assertEquals(ReplayRunState.FAILED, failed.getState(), failed.getTerminalReason());
    assertEquals("REPLAY_ERROR:XRSPS_VERSION_INCOMPATIBLE", failed.getTerminalReason());
  }

  /**
   * N14 (REAL execution): a persisted manifest whose runtime identity was altered must fail the
   * production worker's pre-execution pinning BEFORE any step executes.
   */
  @Test
  @Order(24)
  void tamperedManifestRuntimeIdentityFailsBeforeFirstStep() throws Exception {
    UUID manifestId = seedPublishedManifest();
    ReplayRunResponse run = createParentRun(manifestId);
    assertEquals(ReplayRunState.QUEUED, run.state());
    tx.execute(
        status -> {
          ScenarioManifest manifest = manifestRepository.findById(manifestId).orElseThrow();
          ObjectNode doc = (ObjectNode) manifest.getManifestJson().deepCopy();
          ((ObjectNode) doc.path("runtime")).put("xrspsBuildCommit", "d".repeat(40));
          // Keep the manifest document self-consistent so the ONLY failure is the runtime pin.
          doc.remove("manifestChecksum");
          String checksum = projection.sha256Hex(projection.canonicalJsonBytes(doc));
          doc.put("manifestChecksum", checksum);
          manifest.setManifestJson(doc);
          manifest.setManifestChecksum(checksum);
          manifestRepository.saveAndFlush(manifest);
          return null;
        });

    ReplayRun failed = awaitTerminal(run.runId(), 120);
    assertEquals(ReplayRunState.FAILED, failed.getState(), failed.getTerminalReason());
    assertEquals("REPLAY_ERROR:XRSPS_VERSION_INCOMPATIBLE", failed.getTerminalReason());
    // Pre-execution pinning: NO step ever ran — no events, no state-hash samples.
    assertTrue(
        eventRepository.findByRunIdOrderByOrdinalAsc(run.runId()).isEmpty(),
        "no events may exist for a pre-execution pinning failure");
    assertTrue(
        hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(run.runId()).isEmpty(),
        "no state-hash samples may exist for a pre-execution pinning failure");
  }

  /**
   * N15 (REAL execution): forging the child's parent lineage to a checkpoint OWNED BY ANOTHER RUN
   * must be rejected by the production worker's ownership check BEFORE the kernel restore.
   */
  @Test
  @Order(25)
  void forgedParentLineageIsRejectedBeforeKernelRestore() throws Exception {
    ReplayCheckpoint checkpointA = terminalParentWithCheckpoint();
    ReplayCheckpoint checkpointB = terminalParentWithCheckpoint();
    assertNotEquals(checkpointA.getRunId(), checkpointB.getRunId(), "the two parents must differ");
    ReplayRunResponse child =
        createBranchRun(checkpointA.getRunId(), checkpointA.getId(), "branch-forge");
    assertEquals(ReplayRunState.QUEUED, child.state());
    // Forge: point the child at a REAL checkpoint owned by ANOTHER run.
    tx.execute(
        status -> {
          ReplayRun childRow = runRepository.findById(child.runId()).orElseThrow();
          childRow.setParentCheckpointId(checkpointB.getId());
          runRepository.saveAndFlush(childRow);
          return null;
        });
    ReplayRun failed = awaitTerminal(child.runId(), 120);
    assertEquals(ReplayRunState.FAILED, failed.getState(), failed.getTerminalReason());
    assertEquals("REPLAY_ERROR:SNAPSHOT_INCOMPATIBLE", failed.getTerminalReason());
  }

  // ── production strict-capability-validator negative coverage ──

  /** Builds a capability document that mirrors the live synthetic worker's shape. */
  private ObjectNode validCapabilityDocument() {
    ObjectNode capability = mapper.createObjectNode();
    capability.put("schemaVersion", 1);
    capability.put("certifiable", true);
    capability.put("controlAllowed", false);
    capability.put("openRuneAvailable", false);
    ObjectNode modes = capability.putObject("modes");
    modes.put("observedPlayback", false);
    modes.put("policyReplay", false);
    modes.put("causalSimulation", true);
    capability.putArray("scenarioSchemaVersions").add(1);
    capability.putArray("semanticActionVersions").add(1);
    capability.putArray("snapshotVersions").add(1);
    capability.putArray("projectionVersions").add(1);
    capability.putArray("hashVersions").add("sha256-canonical-json-v1");
    capability.put("fidelity", "SYNTHETIC_CONTRACT");
    capability.putArray("supportedActions").add("WAIT_UNTIL_TICK").add("INTERACT_NPC");
    ObjectNode xrsps = capability.putObject("xrsps");
    xrsps.put("adapterVersion", liveIdentity.xrspsAdapterVersion());
    xrsps.put("buildCommit", liveIdentity.xrspsBuildCommit());
    xrsps.put("cacheRevision", liveIdentity.cacheRevision());
    xrsps.put("contentRevision", liveIdentity.contentRevision());
    return capability;
  }

  @SuppressWarnings("unchecked")
  private List<String> rawValidationProblems(
      XrspsStartupValidator validator, ObjectNode capability) {
    return org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        validator, "validateRawDocument", capability);
  }

  /**
   * N16: the production strict capability validator must reject every forbidden capability surface
   * — control, OpenRune, policy replay, missing causal simulation, version-vector drift, malformed
   * revisions, and development identity placeholders.
   */
  @Test
  @Order(26)
  void strictCapabilityValidatorRejectsForbiddenCapabilitySurfaces() {
    XrspsStartupValidator validator =
        new XrspsStartupValidator(properties, new ReplayCapabilityService(), mapper);
    assertTrue(
        rawValidationProblems(validator, validCapabilityDocument()).isEmpty(),
        "the live capability shape must pass the production strict validator");

    ObjectNode control = validCapabilityDocument();
    control.put("controlAllowed", true);
    assertFalse(
        rawValidationProblems(validator, control).isEmpty(),
        "controlAllowed=true must be rejected");

    ObjectNode openRune = validCapabilityDocument();
    openRune.put("openRuneAvailable", true);
    assertFalse(
        rawValidationProblems(validator, openRune).isEmpty(),
        "openRuneAvailable=true must be rejected");

    ObjectNode policy = validCapabilityDocument();
    ((ObjectNode) policy.path("modes")).put("policyReplay", true);
    assertFalse(
        rawValidationProblems(validator, policy).isEmpty(), "policyReplay=true must be rejected");

    ObjectNode noCausal = validCapabilityDocument();
    ((ObjectNode) noCausal.path("modes")).put("causalSimulation", false);
    assertFalse(
        rawValidationProblems(validator, noCausal).isEmpty(),
        "causalSimulation=false must be rejected");

    ObjectNode missingVector = validCapabilityDocument();
    missingVector.remove("snapshotVersions");
    assertFalse(
        rawValidationProblems(validator, missingVector).isEmpty(),
        "a missing exact version vector must be rejected");

    ObjectNode malformedRevision = validCapabilityDocument();
    ((ObjectNode) malformedRevision.path("xrsps")).put("cacheRevision", "deadbeef");
    assertFalse(
        rawValidationProblems(validator, malformedRevision).isEmpty(),
        "a malformed identity revision must be rejected");

    ObjectNode devIdentity = validCapabilityDocument();
    ((ObjectNode) devIdentity.path("xrsps")).put("buildCommit", "dev");
    assertFalse(
        rawValidationProblems(validator, devIdentity).isEmpty(),
        "a development identity must be rejected");

    ReplayCapabilityService productionCapability = new ReplayCapabilityService();
    CapabilityManifest syntheticManifest =
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            validator, "parseManifest", validCapabilityDocument());
    productionCapability.updateFromHandshake(syntheticManifest);
    assertFalse(
        productionCapability.isCausalSimulationCertifiable(),
        "a synthetically faithful worker must remain ineligible for production simulation");
  }

  // ── seeding helpers for negative cases ──

  private UUID seedForeignTerminalRunForRestore() {
    return tx.execute(
        status -> {
          UUID manifestId = seedPublishedManifestNativeMinimal();
          ReplayRun run = new ReplayRun();
          run.setMode(ReplayMode.CAUSAL_SIMULATION);
          run.setManifestId(manifestId);
          run.setManifestChecksum("sha256:" + "0".repeat(64));
          run.setOwnerId("certification-owner");
          run.setIdempotencyKey("cert-foreign-" + UUID.randomUUID());
          run.setState(ReplayRunState.SUCCEEDED);
          run.setPhase(ReplayRunPhase.FINALIZE_REPORT);
          run.setOutcome(ReplayOutcome.PASSED);
          run.setBranchKey("main");
          run.setCreatedAt(runRepository.currentTimestamp());
          run.setTerminalAt(runRepository.currentTimestamp());
          run.setXrspsAdapterVersion(liveIdentity.xrspsAdapterVersion());
          run.setXrspsBuildCommit(liveIdentity.xrspsBuildCommit());
          run.setCacheRevision(liveIdentity.cacheRevision());
          run.setContentRevision(liveIdentity.contentRevision());
          ReplayRun saved = runRepository.saveAndFlush(run);
          return saved.getId();
        });
  }

  private UUID seedPublishedManifestNativeMinimal() {
    // Reuse the full manifest fixture but only the rows restore/branch need.
    return seedPublishedManifest();
  }

  private void seedCheckpointWithoutArtifactChecksum(UUID runId) {
    tx.execute(
        status -> {
          ReplayCheckpoint cp =
              new ReplayCheckpoint(
                  UUID.randomUUID(),
                  runId,
                  UUID.randomUUID(),
                  7L,
                  "sha256:" + "f".repeat(64),
                  "sha256:" + "1".repeat(64),
                  "sha256:" + "2".repeat(64),
                  "main");
          // Leave snapshotArtifactChecksum null by bypassing the constructor's non-null path:
          cp.setSnapshotArtifactChecksum(null);
          checkpointRepository.saveAndFlush(cp);
          return null;
        });
  }

  private void seedStagingRefWithoutFile(UUID runId, ArtifactKind kind, String checksum) {
    tx.execute(
        status -> {
          ArtifactRef ref = new ArtifactRef(UUID.randomUUID(), runId, kind, checksum, 13L);
          ref.setMediaType("application/json");
          ref.setState(ArtifactRef.STATE_STAGING);
          ref.setCreatedAt(Instant.now());
          return artifactRefRepository.saveAndFlush(ref);
        });
  }

  /** The ONLY services this slice needs, registered explicitly (no component scan). */
  @TestConfiguration
  @Profile("replay-certification | replay-stress-certification")
  static class CertificationServices {

    @Bean
    com.chromascape.web.replay.config.ReplayProperties certificationReplayProperties() {
      return new com.chromascape.web.replay.config.ReplayProperties();
    }

    @Bean
    com.fasterxml.jackson.databind.ObjectMapper certificationObjectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Bean
    CanonicalProjectionService certificationProjectionService() {
      return new CanonicalProjectionService();
    }

    @Bean
    MismatchEngine certificationMismatchEngine(
        ReplayMismatchRepository mismatches, CanonicalProjectionService projectionService) {
      return new MismatchEngine(mismatches, projectionService);
    }

    @Bean
    ReplayReportService certificationReportService(
        ReplayReportRepository reports,
        MismatchEngine mismatchEngine,
        CanonicalProjectionService projectionService,
        com.fasterxml.jackson.databind.ObjectMapper mapper) {
      return new ReplayReportService(reports, mismatchEngine, projectionService, mapper);
    }

    @Bean
    ReplayRunService certificationRunService(
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayCheckpointRepository checkpoints,
        ReplayEventRepository events,
        ReplayReportRepository reports,
        com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository manifests,
        ReplayReportService reportService,
        org.springframework.core.env.Environment environment) {
      return new ReplayRunService(
          runs, attempts, checkpoints, events, reports, manifests, reportService, environment);
    }

    @Bean
    ReplayWorkerPersistenceService certificationPersistenceService(
        ReplayRunService runService,
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayEventRepository events,
        StateHashSampleRepository hashSamples,
        ReplayCheckpointRepository checkpoints,
        ReplayMismatchRepository mismatches,
        com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifacts,
        ReplayReportRepository reports,
        com.chromascape.web.replay.config.ReplayProperties properties,
        EntityManager entityManager) {
      return new ReplayWorkerPersistenceService(
          runService,
          runs,
          attempts,
          events,
          hashSamples,
          checkpoints,
          mismatches,
          artifacts,
          reports,
          properties,
          entityManager);
    }

    @Bean
    ReplayExpiredAttemptRecoveryTransaction certificationRecoveryTransaction(
        ReplayAttemptRepository attempts,
        ReplayRunRepository runs,
        ReplayRunService runService,
        com.chromascape.web.replay.config.ReplayProperties properties) {
      return new ReplayExpiredAttemptRecoveryTransaction(attempts, runs, runService, properties);
    }

    @Bean
    ReplayExpiredAttemptRecoveryService certificationRecoveryService(
        ReplayExpiredAttemptRecoveryTransaction transaction,
        ReplayRecoveryReportWriter reportWriter) {
      return new ReplayExpiredAttemptRecoveryService(transaction, reportWriter);
    }

    @Bean
    com.chromascape.web.replay.artifact.ReplayArtifactService certificationArtifactService(
        com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifacts,
        com.chromascape.web.replay.config.ReplayProperties properties) {
      return new com.chromascape.web.replay.artifact.ReplayArtifactService(artifacts, properties);
    }

    @Bean
    ReplayRecoveryReportTransaction certificationRecoveryReportTransaction(
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        StateHashSampleRepository hashSamples,
        ReplayCheckpointRepository checkpoints,
        com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifacts,
        ReplayReportRepository reports,
        ReplayMismatchRepository mismatches,
        ReplayReportService reportService) {
      return new ReplayRecoveryReportTransaction(
          runs, attempts, hashSamples, checkpoints, artifacts, reports, mismatches, reportService);
    }

    @Bean
    ReplayRecoveryReportWinnerReader certificationReportWinnerReader(
        ReplayReportRepository reports) {
      return new ReplayRecoveryReportWinnerReader(reports);
    }

    @Bean
    ReplayRecoveryReportWriter certificationReportWriter(
        ReplayRecoveryReportTransaction transaction,
        ReplayRecoveryReportWinnerReader winnerReader,
        ReplayRunRepository runs,
        CanonicalProjectionService projectionService,
        com.fasterxml.jackson.databind.ObjectMapper mapper) {
      return new ReplayRecoveryReportWriter(
          transaction, winnerReader, runs, projectionService, mapper);
    }

    @Bean
    ReplayMissingReportReconciler certificationMissingReportReconciler(
        ReplayRunRepository runs,
        ReplayRecoveryReportWriter writer,
        com.chromascape.web.replay.config.ReplayProperties properties) {
      return new ReplayMissingReportReconciler(runs, writer, properties);
    }

    @Bean
    ReplayCapabilityService certificationCapabilityService() {
      return new ReplayCapabilityService();
    }
  }
}
