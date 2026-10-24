package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.api.dto.*;
import com.chromascape.web.replay.artifact.*;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.*;
import com.chromascape.web.replay.run.dto.BranchReplayRunRequest;
import com.chromascape.web.replay.run.entity.*;
import com.chromascape.web.replay.run.repository.*;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real current-schema PostgreSQL and artifact persistence. Native fixtures are authored and
 * non-certifying; public admission and the production OS sandbox are NOT qualified by this lane.
 * Missing prerequisites fail, never skip. Every database/container belongs to this test only.
 */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/ai-player-current",
      "spring.flyway.schemas=ai_player_dev",
      "spring.flyway.default-schema=ai_player_dev",
      "spring.flyway.baseline-on-migrate=false",
      "spring.flyway.out-of-order=false",
      "spring.flyway.clean-disabled=true",
      "spring.flyway.group=true",
      "spring.jpa.properties.hibernate.default_schema=ai_player_dev",
      "spring.main.lazy-initialization=true",
      "spring.datasource.hikari.maximum-pool-size=8",
      "replay.worker.lease-duration-seconds=300"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = NativeCheckpointPersistenceTest.Application.class)
@ActiveProfiles("native-checkpoint-persistence")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(NativeCheckpointPersistenceTest.Services.class)
class NativeCheckpointPersistenceTest {
  /**
   * Selected before Spring evaluates any dynamic property, so ordinary lanes never depend on
   * JUnit's {@code @TempDir} extension ordering. Restart lanes keep their persistent owned root;
   * every other lane gets a lifecycle-owned temporary directory outside the candidate checkout.
   */
  private static final Path CANDIDATE_DEFAULT_ROOT =
      Path.of(".chromascape/replay-artifacts").toAbsolutePath().normalize();

  private static final Path BOUND_ARTIFACT_ROOT = selectArtifactRoot();

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EntityScan("com.chromascape.web.replay")
  @EnableJpaRepositories("com.chromascape.web.replay")
  static class Application {}

  private static PostgreSQLContainer<?> postgres;
  private static String jdbcUrl;
  private static String username;
  private static String password;

  @BeforeAll
  static void startDatabase() {
    database();
  }

  private static synchronized void database() {
    if (jdbcUrl != null) return;
    String supplied = System.getenv("SC276_NATIVE_CHECKPOINT_JDBC_URL");
    if (supplied != null) {
      // Same exact disposable loopback target used by the strict schema lane. The owning
      // orchestrator must verify its container ID/random label/port before launching this test
      // and stop that resource in finally. An arbitrary/shared datasource is not an option.
      com.chromascape.web.aiplayer.schema.AiPlayerSchemaTarget.validate(
          supplied, Set.of(com.chromascape.web.aiplayer.schema.AiPlayerSchemaTarget.PROFILE));
      jdbcUrl = supplied;
      username = "sc665";
      password = "sc665-disposable-test";
      return;
    }
    postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sc276_native_checkpoint_owned")
            .withUsername("test")
            .withPassword("test")
            .withLabel("codex.task", "sc274-sc276-native-checkpoint-persistence")
            .waitingFor(HostPortWaitStrategy.postgres());
    postgres.start();
    jdbcUrl = postgres.getJdbcUrl();
    username = postgres.getUsername();
    password = postgres.getPassword();
  }

  @AfterAll
  static void stopDatabase() {
    // Externally provisioned test resources belong to their orchestrator, not this test.
    if (postgres != null) postgres.stop();
  }

  private static Path selectArtifactRoot() {
    String restartRoot = System.getenv("SC276_NATIVE_RESTART_ROOT");
    if (restartRoot != null && !restartRoot.isBlank()) {
      Path root = Path.of(restartRoot).toAbsolutePath().normalize();
      Path artifacts = root.resolve("artifacts").normalize();
      if (!Files.isRegularFile(root.resolve("owner-token"))
          || !Files.isDirectory(artifacts)
          || !artifacts.startsWith(root)) {
        throw new IllegalStateException("Native restart requires its owned artifact directory");
      }
      return artifacts;
    }
    try {
      Path created =
          Files.createTempDirectory("sc276-native-checkpoint-artifacts-").toAbsolutePath();
      Runtime.getRuntime()
          .addShutdownHook(new Thread(() -> deleteRecursively(created), "sc276-artifact-cleanup"));
      return created.normalize();
    } catch (IOException error) {
      throw new IllegalStateException("Cannot create the owned native artifact root", error);
    }
  }

  private static void deleteRecursively(Path root) {
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best-effort cleanup of the owned temporary lane only.
                }
              });
    } catch (IOException ignored) {
      // Best-effort cleanup of the owned temporary lane only.
    }
  }

  private static Map<String, String> inventory(Path root) {
    Map<String, String> entries = new TreeMap<>();
    if (!Files.isDirectory(root)) return entries;
    try (var paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                try {
                  entries.put(
                      root.relativize(path).toString(),
                      path.toFile().length() + ":" + Files.getLastModifiedTime(path).toMillis());
                } catch (IOException error) {
                  throw new UncheckedIOException(error);
                }
              });
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    return entries;
  }

  /**
   * {@link ReplayProperties} is a {@code @ConfigurationProperties} bean, so environment binding
   * overwrites values set programmatically in the test bean factory. Every lane must therefore
   * publish its owned artifact root and authored run budget through the environment. Ordinary lanes
   * use the lifecycle-owned temporary directory selected above; restart lanes retain the persistent
   * owned restart root. Production defaults and worker resource limits are unchanged.
   */
  @DynamicPropertySource
  static void replayLaneProperties(DynamicPropertyRegistry registry) {
    registry.add("replay.artifacts.root", () -> BOUND_ARTIFACT_ROOT.toString());
    registry.add("replay.worker.run-timeout-seconds", () -> 3600);
    registry.add("replay.worker.lease-duration-seconds", () -> 300);
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> {
          database();
          boolean schemaSelected = jdbcUrl.contains("currentSchema=ai_player_dev");
          return jdbcUrl
              + (jdbcUrl.contains("?") ? "&" : "?")
              + (schemaSelected ? "" : "currentSchema=ai_player_dev&")
              + "options=-c%20lock_timeout%3D5000%20-c%20statement_timeout%3D15000";
        });
    registry.add(
        "spring.datasource.username",
        () -> {
          database();
          return username;
        });
    registry.add(
        "spring.datasource.password",
        () -> {
          database();
          return password;
        });
  }

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReplayRunRepository runs;
  @Autowired ReplayAttemptRepository attempts;
  @Autowired ReplayCheckpointRepository checkpoints;
  @Autowired ReplayEventRepository events;
  @Autowired ArtifactRefRepository refs;
  @Autowired ReplayReportRepository reports;
  @Autowired ScenarioManifestRepository manifests;
  @Autowired ReplayMismatchRepository mismatches;
  @Autowired ReplayRunService runService;
  @Autowired ReplayWorkerPersistenceService persistence;
  @Autowired ReplayArtifactService artifacts;
  @Autowired ReplayReportService reportService;
  @Autowired ReplayLeaseHeartbeat heartbeats;
  @Autowired ReplayProperties properties;
  @Autowired CanonicalProjectionService hashes;
  @Autowired ObjectMapper json;

  private Map<String, String> defaultRootInventory;

  /**
   * Assert the effective environment-bound root before any test write. The candidate default
   * directory is never acceptable, and historical files there stay untouched.
   */
  @BeforeEach
  void assertOwnedArtifactRootBeforeWrites() throws IOException {
    Path effective = Path.of(properties.getArtifacts().getRoot()).toAbsolutePath().normalize();
    assertThat(effective).isEqualTo(BOUND_ARTIFACT_ROOT);
    assertThat(effective).isNotEqualTo(CANDIDATE_DEFAULT_ROOT);
    assertThat(effective.startsWith(CANDIDATE_DEFAULT_ROOT)).isFalse();
    Files.createDirectories(effective);
    System.out.println("SC276_NATIVE_ARTIFACT_ROOT=" + effective);
    defaultRootInventory = inventory(CANDIDATE_DEFAULT_ROOT);
  }

  @AfterEach
  void artifactWritesStayInsideTheOwnedRoot() {
    assertThat(inventory(CANDIDATE_DEFAULT_ROOT))
        .as("candidate default artifact directory must not receive new writes")
        .isEqualTo(defaultRootInventory);
    Path effective = Path.of(properties.getArtifacts().getRoot()).toAbsolutePath().normalize();
    assertThat(effective).isEqualTo(BOUND_ARTIFACT_ROOT);
  }

  @Test
  void fullCurrentMigrationChainAndReplayJpaSupportNativeBindingsAndArtifacts() throws Exception {
    flyway.validate();
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(
            java.util.Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null)
                .map(migration -> migration.getVersion().toString())
                .toList())
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE"
                    + " table_schema='ai_player_dev' AND table_name='replay_checkpoints' AND"
                    + " column_name='restore_context'",
                String.class))
        .isEqualTo("text");
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE"
                    + " table_schema='ai_player_dev' AND table_name='replay_checkpoints' AND"
                    + " column_name='chain_artifact_checksum'",
                String.class))
        .isEqualTo("character varying");
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                    + " conrelid='ai_player_dev.artifact_refs'::regclass AND"
                    + " conname='artifact_refs_kind_check'",
                String.class))
        .contains("CHAIN", "PROJECTION", "OUTCOME");
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE"
                    + " table_schema='ai_player_dev' AND table_name='state_hash_samples' AND"
                    + " column_name='transition_ordinal'",
                String.class))
        .isEqualTo("bigint");
    assertThat(jdbc.queryForObject("SELECT identity FROM ai_player_schema_identity", String.class))
        .isEqualTo("sc276-current-v2");
    // Review-only actual export. This replay-only lane does not bypass or claim the full
    // startup guard; the checked-in pin must be reconciled separately with this real result.
    try (var connection = jdbc.getDataSource().getConnection()) {
      System.out.println(
          "SC276_CURRENT_SCHEMA_FINGERPRINT="
              + com.chromascape.web.aiplayer.schema.AiPlayerCurrentSchema.fingerprint(connection));
    }
  }

  @Test
  void currentSchemaPreservesNullableBindingsAndEnforcesNativeEvidenceConstraints() {
    ObjectNode manifest = authoredManifest();
    stampManifest(manifest);
    try (var f = new StorageFixture(manifest)) {
      jdbc.update(
          "INSERT INTO"
              + " state_hash_samples(run_id,tick,sequence,projection_version,state_hash,chain_hash)"
              + " VALUES (?,0,0,1,?,?)",
          f.run.getId(),
          "sha256:" + "a".repeat(64),
          "sha256:" + "b".repeat(64));
      assertThat(
              jdbc.queryForObject(
                  "SELECT transition_ordinal FROM state_hash_samples WHERE run_id=?",
                  Long.class,
                  f.run.getId()))
          .isNull();
      assertThat(
              jdbc.update(
                  "UPDATE state_hash_samples SET transition_ordinal=0 WHERE run_id=?",
                  f.run.getId()))
          .isEqualTo(1);
      assertConstraintRejected(
          () ->
              jdbc.update(
                  "UPDATE state_hash_samples SET transition_ordinal=-1 WHERE run_id=?",
                  f.run.getId()),
          "state_hash_samples_ordinal_check");
      assertThat(
              jdbc.queryForObject(
                  "SELECT transition_ordinal FROM state_hash_samples WHERE run_id=?",
                  Long.class,
                  f.run.getId()))
          .isZero();
      for (String kind : List.of("CHAIN", "PROJECTION", "OUTCOME")) {
        assertThat(insertSchemaOnlyReference(f.run.getId(), kind)).isEqualTo(1);
      }
      assertConstraintRejected(
          () -> insertSchemaOnlyReference(f.run.getId(), "UNKNOWN"), "artifact_refs_kind_check");
      assertThat(refs.findByRunIdOrderByCreatedAtAsc(f.run.getId())).hasSize(3);
      // SQL-only constraint fixtures remain STAGING; they are never evidence of file publication.
      assertThat(refs.findByRunIdAndState(f.run.getId(), ArtifactRef.STATE_FINALIZED)).isEmpty();
    }
  }

  private int insertSchemaOnlyReference(UUID runId, String kind) {
    return jdbc.update(
        "INSERT INTO artifact_refs(id,run_id,kind,checksum,size_bytes,state,created_at)"
            + " VALUES (?,?,?,?,0,'STAGING',CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        runId,
        kind,
        "sha256:" + "e".repeat(64));
  }

  private void assertConstraintRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable write, String constraint) {
    var rejected = catchThrowableOfType(write, DataIntegrityViolationException.class);
    assertThat(rejected).isNotNull();
    assertThat(rejected.getMostSpecificCause())
        .isInstanceOfSatisfying(
            java.sql.SQLException.class,
            cause -> {
              assertThat(cause.getSQLState()).isEqualTo("23514");
              assertThat(cause.getMessage()).contains("\"" + constraint + "\"");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"generation", "worker", "attempt", "expired", "cancelled"})
  void rejectedCheckpointFenceLeavesNoReferenceOrRow(String rejection) {
    ObjectNode manifest = authoredManifest();
    stampManifest(manifest);
    var state = NativeCheckpointRestoreTest.prefix(manifest, false);
    ObjectNode snapshot = NativeCheckpointRestoreTest.snapshot(manifest, state);
    try (var f = new StorageFixture(manifest)) {
      ReplayFence fence = f.fence;
      switch (rejection) {
        case "generation" -> fence =
            new ReplayFence(fence.runId(), fence.attemptId(), 1, 2, fence.workerIdentity());
        case "worker" -> fence =
            new ReplayFence(fence.runId(), fence.attemptId(), 1, 1, "foreign-worker");
        case "attempt" -> fence =
            new ReplayFence(fence.runId(), UUID.randomUUID(), 1, 1, fence.workerIdentity());
        case "expired" -> jdbc.update(
            "UPDATE replay_attempts SET lease_expires_at=CURRENT_TIMESTAMP-interval '1 hour' WHERE"
                + " id=?",
            fence.attemptId());
        case "cancelled" -> runService.cancel(fence.runId(), "authored-storage-owner");
        default -> throw new AssertionError(rejection);
      }
      ReplayFence rejected = fence;
      if (rejection.equals("cancelled")) {
        assertThatThrownBy(() -> f.record(rejected, state, snapshot))
            .isInstanceOf(ReplayCancellationRequested.class);
      } else {
        assertThatThrownBy(() -> f.record(rejected, state, snapshot))
            .isInstanceOf(ReplayException.class);
      }
      assertThat(refs.findByRunIdOrderByCreatedAtAsc(f.run.getId())).isEmpty();
      assertThat(checkpointCount(f.run.getId())).isZero();
    }
  }

  @Test
  void nativeContextAndSnapshotRoundTripAndDuplicateTupleRollsBackNewReference() throws Exception {
    ObjectNode manifest = authoredManifest();
    stampManifest(manifest);
    var state = NativeCheckpointRestoreTest.prefix(manifest, false);
    ObjectNode snapshot = NativeCheckpointRestoreTest.snapshot(manifest, state);
    try (var f = new StorageFixture(manifest)) {
      f.record(f.fence, state, snapshot);
      var saved = checkpoints.findByRunIdAndBranchKeyOrderByTickAsc(f.run.getId(), "main").get(0);
      var ref =
          refs.findByRunIdAndKindAndChecksum(
                  f.run.getId(), ArtifactKind.SNAPSHOT, saved.getSnapshotArtifactChecksum())
              .orElseThrow();
      assertThat(saved.getCreatedAt()).isEqualTo(ref.getCreatedAt());
      assertThat(saved.getChainArtifactChecksum()).isNull();
      assertThat(json.readTree(saved.getRestoreContext()).path("schema").asText())
          .isEqualTo(NativeCheckpointContext.SCHEMA);
      assertThat(saved.getActionIndex()).isEqualTo(state.executedActions);
      ObjectNode other = snapshot.deepCopy();
      ((ObjectNode) other.path("eventQueue")).put("nextOrdinal", 8);
      other.remove("checksum");
      other.put("checksum", hashes.stateHash(other));
      assertThatThrownBy(() -> f.record(f.fence, state, other))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(checkpointCount(f.run.getId())).isEqualTo(1);
      assertThat(refs.findByRunIdOrderByCreatedAtAsc(f.run.getId())).hasSize(1);
      assertThat(checkpoints.findById(saved.getId()).orElseThrow().getRestoreContext())
          .isEqualTo(saved.getRestoreContext());
    }
  }

  @Test
  void realPublicationReservationReportAndOwnerScopedBranchKeepNativeAnchor() throws Exception {
    ObjectNode manifest = authoredManifest();
    ((ObjectNode) manifest.path("execution")).put("maximumActions", 1);
    stampManifest(manifest);
    var state = NativeCheckpointRestoreTest.prefix(manifest, true);
    ObjectNode snapshot = NativeCheckpointRestoreTest.snapshot(manifest, state);
    try (var f = new StorageFixture(manifest)) {
      f.record(f.fence, state, snapshot);
      f.worker.captureNativeOutcome(f.fence, state);
      var saved = checkpoints.findByRunIdAndBranchKeyOrderByTickAsc(f.run.getId(), "main").get(0);
      var request =
          new BranchReplayRunRequest(
              saved.getId().toString(), "native-child", "child-" + UUID.randomUUID());
      assertThatThrownBy(() -> runService.branch(f.run.getId(), request, "authored-storage-owner"))
          .isInstanceOf(ReplayException.class);
      persistence.updatePhase(f.fence, ReplayRunPhase.EXECUTE, ReplayRunPhase.COMPARE);
      var reservation = persistence.reserveSuccessfulFinalization(f.fence, ReplayOutcome.PASSED);
      var active = runs.findById(f.run.getId()).orElseThrow();
      var attempt = attempts.findById(f.fence.attemptId()).orElseThrow();
      var addresses =
          refs.findByRunIdOrderByCreatedAtAsc(active.getId()).stream()
              .map(ArtifactRef::getChecksum)
              .toList();
      var prepared =
          reportService.prepareReport(
              active,
              attempt,
              ReplayOutcome.PASSED,
              "NATIVE_ACTION_LIMIT_SOURCE_PREFIX",
              state.executedActions,
              1,
              state.lastStateHash,
              state.chainHash,
              addresses,
              reservation.reservedAt(),
              null);
      persistence.registerArtifact(
          f.fence,
          artifacts.prepareArtifact(
              active.getId(), ArtifactKind.REPORT, prepared.canonicalBytes(), "application/json"));
      var receipt = artifacts.publishArtifacts(active.getId());
      var report = reportService.materialize(prepared, active, attempt);
      assertThatThrownBy(
              () ->
                  persistence.finalizeSuccessfulRun(
                      f.fence,
                      reservation,
                      new ArtifactPublicationReceipt(active.getId(), List.of()),
                      report,
                      ReplayOutcome.PASSED))
          .isInstanceOf(ReplayException.class);
      assertThat(reports.findByRunId(active.getId())).isEmpty();
      assertThat(refs.findByRunIdAndState(active.getId(), ArtifactRef.STATE_STAGING)).hasSize(3);
      persistence.finalizeSuccessfulRun(
          f.fence, reservation, receipt, report, ReplayOutcome.PASSED);
      var terminal = runs.findById(active.getId()).orElseThrow();
      assertThat(terminal.getState()).isEqualTo(ReplayRunState.SUCCEEDED);
      assertThat(terminal.getTerminalAt()).isEqualTo(reservation.reservedAt());
      assertThat(attempts.findById(attempt.getId()).orElseThrow().getTerminalAt())
          .isEqualTo(reservation.reservedAt());
      var storedReport = reports.findByRunId(active.getId()).orElseThrow();
      assertThat(storedReport.getTerminalAt())
          .isEqualTo(reservation.reservedAt().toInstant(ZoneOffset.UTC));
      for (var ref : refs.findByRunIdOrderByCreatedAtAsc(active.getId())) {
        assertThat(ref.getState()).isEqualTo(ArtifactRef.STATE_FINALIZED);
        assertThat(ref.getFinalizedAt())
            .isEqualTo(reservation.reservedAt().toInstant(ZoneOffset.UTC));
        assertThat(artifacts.readFinalized(ref)).hasSize((int) ref.getSizeBytes());
      }
      var snapshotRef =
          refs.findByRunIdAndKindAndChecksum(
                  active.getId(), ArtifactKind.SNAPSHOT, saved.getSnapshotArtifactChecksum())
              .orElseThrow();
      // A JSON round trip narrows small LongNodes to IntNodes. The durable contract is exact
      // canonical bytes and their content address, independent of Java's in-memory node type.
      byte[] publishedSnapshot = artifacts.readFinalized(snapshotRef);
      assertThat(publishedSnapshot).isEqualTo(hashes.canonicalJsonBytes(snapshot));
      assertThat(hashes.sha256Hex(publishedSnapshot))
          .isEqualTo(saved.getSnapshotArtifactChecksum());
      assertThatThrownBy(() -> runService.branch(active.getId(), request, "foreign-owner"))
          .isInstanceOf(ReplayException.class);
      UUID childId = runService.branch(active.getId(), request, "authored-storage-owner").runId();
      assertThat(runService.branch(active.getId(), request, "authored-storage-owner").runId())
          .isEqualTo(childId);
      var child = runs.findById(childId).orElseThrow();
      assertThat(child.getParentRunId()).isEqualTo(active.getId());
      assertThat(child.getParentCheckpointId()).isEqualTo(saved.getId());
      assertThat(child.getManifestChecksum()).isEqualTo(active.getManifestChecksum());
      assertThat(child.getStopPolicy()).isEqualTo(active.getStopPolicy());
      assertThat(child.isControlAllowed()).isFalse();
      assertThat(manifests.findById(child.getManifestId()).orElseThrow().getPublishState())
          .isEqualTo("DRAFT");
      // These deliberately DRAFT/INCOMPLETE authored rows prove storage, not public admission.
    }
  }

  private ObjectNode authoredManifest() {
    ObjectNode manifest = NativeCheckpointRestoreTest.manifest();
    ((ObjectNode) manifest.path("runtime"))
        .put("cacheRevision", "sha256:" + "c".repeat(64))
        .put("contentRevision", "sha256:" + "d".repeat(64));
    return manifest;
  }

  private void stampManifest(ObjectNode manifest) {
    manifest.remove("manifestChecksum");
    manifest.put("manifestChecksum", hashes.stateHash(manifest));
  }

  private long checkpointCount(UUID runId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM replay_checkpoints WHERE run_id=?", Long.class, runId);
  }

  /** Real repositories/transactions/artifact root; only the snapshot transport is controlled. */
  private final class StorageFixture implements AutoCloseable {
    final ReplayRun run;
    final ReplayFence fence;
    final ObjectNode manifest;
    final RuntimeIdentity runtime;
    final XrspsKernelProcess kernel = mock(XrspsKernelProcess.class);
    final ReplayLeaseHeartbeat.Handle heartbeat;
    final ReplayWorker worker;

    StorageFixture(ObjectNode manifest) throws RuntimeException {
      this.manifest = manifest;
      this.runtime = json.convertValue(manifest.path("runtime"), RuntimeIdentity.class);
      UUID manifestId = draftManifest(manifest);
      ReplayRun queued = new ReplayRun();
      queued.setManifestId(manifestId);
      queued.setManifestChecksum(manifest.path("manifestChecksum").asText());
      queued.setManifestVersion(2);
      queued.setMode(ReplayMode.CAUSAL_SIMULATION);
      queued.setOwnerId("authored-storage-owner");
      queued.setIdempotencyKey("authored-storage-" + UUID.randomUUID());
      queued.setState(ReplayRunState.QUEUED);
      queued.setPhase(ReplayRunPhase.PREFLIGHT);
      queued.setFidelity("SYNTHETIC_CONTRACT");
      queued.setCreatedAt(runs.currentTimestamp());
      UUID runId = runs.saveAndFlush(queued).getId();
      var claim = runService.claimAtomically(runId, "authored-storage-worker");
      assertThat(claim).isNotNull();
      this.fence = claim.fence();
      this.heartbeat = heartbeats.start(fence);
      persistence.transitionStarting(fence);
      persistence.transitionRunning(fence, ReplayRunPhase.EXECUTE);
      persistence.storeRuntimeIdentity(fence, runtime);
      this.run = runs.findById(runId).orElseThrow();
      this.worker =
          new ReplayWorker(
              runs,
              attempts,
              runService,
              manifests,
              checkpoints,
              refs,
              mismatches,
              persistence,
              heartbeats,
              mock(ReplayExpiredAttemptRecoveryService.class),
              artifacts,
              new MismatchEngine(mismatches, hashes),
              reportService,
              hashes,
              properties,
              json);
    }

    void record(ReplayFence selected, ReplayWorker.ExecutionState state, ObjectNode snapshot) {
      when(kernel.send(eq("snapshot"), isNull(), anyLong()))
          .thenReturn(
              NativeCheckpointRestoreTest.message(
                  json.createObjectNode().set("snapshot", snapshot)));
      worker.takeCheckpoint(
          kernel,
          heartbeat,
          selected,
          run,
          state.nativeLedger.state().path("tick").asLong(),
          state.executedActions - 1,
          state,
          manifest,
          runtime);
    }

    @Override
    public void close() {
      heartbeat.close();
      worker.close();
    }
  }

  /**
   * Complete NOT NULL rows, explicitly unqualified: never fabricate published/complete authority.
   */
  private UUID draftManifest(ObjectNode manifest) {
    String checksum = manifest.path("manifestChecksum").asText();
    var existing =
        jdbc.queryForList(
            "SELECT id FROM scenario_manifests WHERE manifest_checksum=?", UUID.class, checksum);
    if (!existing.isEmpty()) return existing.get(0);
    UUID curation = UUID.randomUUID(), id = UUID.randomUUID();
    String recording = "authored-storage-" + id;
    jdbc.update(
        "INSERT INTO"
            + " recording_curations(id,recording_id,debug_session_id,version,schema_version,state,role,content_json,content_checksum,created_by,created_at)"
            + " VALUES (?,?,?,1,1,'DRAFT','HELD_OUT',CAST(? AS"
            + " jsonb),?,'authored-storage-test',CURRENT_TIMESTAMP)",
        curation,
        recording,
        "authored-session",
        "{\"origin\":\"AUTHORED_TEST_ONLY\"}",
        checksum);
    jdbc.update(
        "INSERT INTO"
            + " scenario_manifests(id,curation_id,recording_id,debug_session_id,recording_checksum,debug_session_checksum,compiler_version,compiler_schema_version,xrsps_adapter_version,xrsps_build_commit,cache_revision,content_revision,seed,fixed_clock_origin,start_tick,end_tick,manifest_json,manifest_checksum,completeness,publish_state,created_at,manifest_version,fidelity,conversion_quality,action_coverage_pct,gap_count,approximation_count)"
            + " VALUES (?,?,?,?,?,?,'authored-storage-test',1,?,?,?,?, 'authored',TIMESTAMP"
            + " '2020-01-01 00:00:00',0,4,CAST(? AS"
            + " jsonb),?,'INCOMPLETE','DRAFT',CURRENT_TIMESTAMP,2,'SYNTHETIC_CONTRACT','INCOMPLETE',0,1,0)",
        id,
        curation,
        recording,
        "authored-session",
        checksum,
        checksum,
        manifest.path("runtime").path("xrspsAdapterVersion").asText(),
        manifest.path("runtime").path("xrspsBuildCommit").asText(),
        manifest.path("runtime").path("cacheRevision").asText(),
        manifest.path("runtime").path("contentRevision").asText(),
        manifest.toString(),
        checksum);
    return id;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Services {
    @Bean
    ReplayProperties properties() {
      var properties = new ReplayProperties();
      // The environment values registered by replayLaneProperties are authoritative; this bean
      // factory value only keeps direct construction consistent with that selection.
      properties.getArtifacts().setRoot(BOUND_ARTIFACT_ROOT.toString());
      return properties;
    }

    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper();
    }

    @Bean
    CanonicalProjectionService projection() {
      return new CanonicalProjectionService();
    }

    @Bean
    MismatchEngine mismatchEngine(
        ReplayMismatchRepository repository, CanonicalProjectionService projection) {
      return new MismatchEngine(repository, projection);
    }

    @Bean
    ReplayReportService reportService(
        ReplayReportRepository reports,
        MismatchEngine mismatch,
        CanonicalProjectionService projection,
        ObjectMapper mapper) {
      return new ReplayReportService(reports, mismatch, projection, mapper);
    }

    @Bean
    ReplayRunService runService(
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayCheckpointRepository checkpoints,
        ReplayEventRepository events,
        ReplayReportRepository reports,
        ScenarioManifestRepository manifests,
        ReplayReportService reportService,
        Environment environment) {
      return new ReplayRunService(
          runs, attempts, checkpoints, events, reports, manifests, reportService, environment);
    }

    @Bean
    ReplayWorkerPersistenceService persistence(
        ReplayRunService service,
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayEventRepository events,
        StateHashSampleRepository samples,
        ReplayCheckpointRepository checkpoints,
        ReplayMismatchRepository mismatches,
        ArtifactRefRepository refs,
        ReplayReportRepository reports,
        ReplayProperties properties,
        EntityManager manager) {
      return new ReplayWorkerPersistenceService(
          service,
          runs,
          attempts,
          events,
          samples,
          checkpoints,
          mismatches,
          refs,
          reports,
          properties,
          manager);
    }

    @Bean
    ReplayArtifactService artifacts(ArtifactRefRepository refs, ReplayProperties properties) {
      return new ReplayArtifactService(refs, properties);
    }

    @Bean
    ReplayLeaseRenewalService renewal(
        ReplayAttemptRepository attempts, ReplayProperties properties) {
      return new ReplayLeaseRenewalService(attempts, properties);
    }

    @Bean(destroyMethod = "destroy")
    ReplayLeaseHeartbeat heartbeat(ReplayLeaseRenewalService renewal, ReplayProperties properties) {
      return new ReplayLeaseHeartbeat(renewal, properties);
    }
  }
}
