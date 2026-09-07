package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.aiplayer.routine.service.CaptureOperationResultService;
import com.chromascape.web.aiplayer.routine.service.CaptureRecordingGateway;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * SC-810 PostgreSQL proof for capture completion and source binding.
 *
 * <p>The recorder result is supplied as a hermetic value, while the source manifest is served by
 * a local ephemeral HTTP server. The test exercises the real transaction manager and database
 * constraints: source fetch is the non-transactional phase, and every source/run/capture/operation
 * write is one rollback-safe phase followed by a separate UNCERTAIN marker transaction.
 */
@SpringBootTest(
    classes = Sc810PostgresTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.target=21",
    "spring.jpa.hibernate.ddl-auto=update"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CaptureSourceIntegrationTest {
  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";
  private static final String OTHER_HASH =
      "sha256:1111111111111111111111111111111111111111111111111111111111111111";
  private static final String VIDEO_HASH =
      "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
  private static final String OTHER_VIDEO_HASH =
      "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
  private static final String ARTIFACT =
      SourceManifestTestFixtures.manifest().path("build").path("artifact_digest").asText();
  private static final String MANIFEST_HASH =
      SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();
  private static final KeyPair SIGNING_KEY = signingKey();
  private static final AtomicBoolean TRANSACTION_ACTIVE_DURING_FETCH = new AtomicBoolean(true);
  private static final AtomicReference<FetchGate> FETCH_GATE = new AtomicReference<>();
  private static final HttpServer MICRO6 = startStub();

  private static HttpServer startStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY).toString()
          .getBytes(StandardCharsets.UTF_8);
      server.createContext("/api/v1/source-manifests/EXAMPLE_TASK", exchange -> {
        TRANSACTION_ACTIVE_DURING_FETCH.set(
            TransactionSynchronizationManager.isActualTransactionActive());
        FetchGate gate = FETCH_GATE.get();
        if (gate != null) {
          gate.entered.countDown();
          try {
            if (!gate.release.await(30, TimeUnit.SECONDS)) {
              throw new IOException("capture source fetch gate timed out");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("capture source fetch gate interrupted", exception);
          }
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, manifest.length);
        exchange.getResponseBody().write(manifest);
        exchange.close();
      });
      server.start();
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException("capture source stub could not start", exception);
    }
  }

  @DynamicPropertySource
  static void sourceProperties(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
    String endpoint = "http://127.0.0.1:" + MICRO6.getAddress().getPort();
    String key = Base64.getEncoder().encodeToString(SIGNING_KEY.getPublic().getEncoded());
    registry.add("micro6.source.transport.base-url", () -> endpoint);
    registry.add("micro6.source.transport.auth-token", () -> "test-source-upstream-token");
    registry.add("micro6.source.transport.actor", () -> "chromascape-test");
    registry.add("chromascape.source.public-key-base64", () -> key);
    registry.add("chromascape.source.public-key-id", () -> "test-key");
    registry.add("chromascape.source.environment", () -> "production");
  }

  @AfterAll
  static void stopStub() {
    MICRO6.stop(0);
  }

  @Autowired private ObjectMapper mapper;
  @Autowired private CaptureOperationResultService captureResults;
  @Autowired private RoutineCaptureOperationRepository operations;
  @Autowired private RoutineCaptureRecordRepository captures;
  @Autowired private CurrentSliceRunRepository runs;
  @Autowired private SourceManifestReferenceRepository manifests;
  @Autowired private SourceRunBindingRepository bindings;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanCreatedRows() {
    tx().executeWithoutResult(status -> {
      bindings.deleteAll();
      bindings.flush();
      runs.deleteAll();
      runs.flush();
      operations.deleteAll();
      operations.flush();
      captures.deleteAll();
      captures.flush();
      manifests.deleteAll();
      manifests.flush();
    });
    FETCH_GATE.set(null);
  }

  @Test
  void sourceFetchIsOutsideTheCompletionWriteTransaction() {
    Fixture fixture = fixture("fetch");
    TRANSACTION_ACTIVE_DURING_FETCH.set(true);

    // Calling the result service from a live transaction proves that its source-fetch proxy
    // suspends the caller before the remote HTTP request. The service then joins the caller only
    // for the local write phase.
    tx().executeWithoutResult(status -> {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture));
    });

    assertFalse(TRANSACTION_ACTIVE_DURING_FETCH.get(),
        "the upstream source request must not execute with a database transaction active");
    assertEquals("SUCCEEDED", operations.findById(fixture.operation().getId()).orElseThrow().getState());
    assertEquals("COMPLETED", captures.findBySessionId(fixture.sessionId()).orElseThrow()
        .getCompletionStatus());
  }

  @Test
  void identityChangingBetweenFetchAndWriteFailsClosed() throws Exception {
    Fixture fixture = fixture("identity-change");
    FetchGate gate = new FetchGate();
    FETCH_GATE.set(gate);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var completion = executor.submit(() ->
          captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture)));
      assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "source fetch did not start");

      tx().executeWithoutResult(status -> {
        RoutineCaptureRecord capture = captures.findBySessionId(fixture.sessionId()).orElseThrow();
        capture.setIdentityJson(write(identity(fixture.runId(), OTHER_HASH)));
        captures.saveAndFlush(capture);
      });
      gate.release.countDown();
      completion.get(30, TimeUnit.SECONDS);
    } finally {
      gate.release.countDown();
      FETCH_GATE.set(null);
      executor.shutdownNow();
    }

    RoutineCaptureOperation operation = operations.findById(fixture.operation().getId()).orElseThrow();
    RoutineCaptureRecord capture = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    assertEquals("UNCERTAIN", operation.getState());
    assertEquals("UNCERTAIN", capture.getCompletionStatus());
    assertFalse(capture.isCompleted());
    assertTrue(findManifest(MANIFEST_HASH).isEmpty(),
        "identity change must prevent the source manifest write");
    assertTrue(bindings.findByRunId(fixture.runId()).isEmpty());
  }

  @Test
  void bindingConflictRollsBackManifestBindingAndCaptureWrite() {
    Fixture fixture = fixture("binding-conflict");
    SourceRunBinding conflicting = persistConflictingBinding(fixture.runId());

    captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture));

    RoutineCaptureOperation operation = operations.findById(fixture.operation().getId()).orElseThrow();
    RoutineCaptureRecord capture = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    assertEquals("UNCERTAIN", operation.getState());
    assertEquals("UNCERTAIN", capture.getCompletionStatus());
    assertFalse(capture.isCompleted());
    assertTrue(findManifest(MANIFEST_HASH).isEmpty());
    assertEquals(conflicting.getManifestHash(), bindings.findByRunId(fixture.runId())
        .orElseThrow().getManifestHash());
    assertEquals("BOUND", runs.findByRunId(fixture.runId()).orElseThrow().getStage());
  }

  @Test
  void runIdentityConflictRollsBackEveryPhaseTwoWrite() {
    String runId = "capture-source-run-conflict";
    CurrentSliceIdentity conflictingIdentity = identityWithSource(runId);
    Fixture fixture = fixture("run-conflict", conflictingIdentity);
    String beforeIdentity = runs.findByRunId(runId).orElseThrow().getIdentityJson();

    captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture));

    RoutineCaptureOperation operation = operations.findById(fixture.operation().getId()).orElseThrow();
    RoutineCaptureRecord capture = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    assertEquals("UNCERTAIN", operation.getState());
    assertEquals("UNCERTAIN", capture.getCompletionStatus());
    assertFalse(capture.isCompleted());
    assertTrue(findManifest(MANIFEST_HASH).isEmpty());
    assertTrue(bindings.findByRunId(runId).isEmpty());
    assertEquals(beforeIdentity, runs.findByRunId(runId).orElseThrow().getIdentityJson());
    assertEquals("BOUND", runs.findByRunId(runId).orElseThrow().getStage());
  }

  @Test
  void captureUpdateFailureRollsBackManifestBindingRunAndCapture() {
    Fixture fixture = fixture("capture-update-failure");
    persistDuplicateRecording(fixture.runId());

    captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture));

    RoutineCaptureOperation operation = operations.findById(fixture.operation().getId()).orElseThrow();
    RoutineCaptureRecord capture = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    assertEquals("UNCERTAIN", operation.getState());
    assertEquals("UNCERTAIN", capture.getCompletionStatus());
    assertFalse(capture.isCompleted());
    assertEquals("BOUND", runs.findByRunId(fixture.runId()).orElseThrow().getStage());
    assertTrue(bindings.findByRunId(fixture.runId()).isEmpty());
    assertTrue(findManifest(MANIFEST_HASH).isEmpty(),
        "a capture update failure must roll back the earlier source writes");
    assertEquals(fixture.recordingId(), captures.findByRecordingId(fixture.recordingId())
        .orElseThrow().getRecordingId());
  }

  @Test
  void exactSuccessfulCompletionRetryReturnsTheExistingImmutableIdentity() {
    Fixture fixture = fixture("idempotent-retry");
    captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture));

    String captureIdentity = captures.findBySessionId(fixture.sessionId()).orElseThrow().getIdentityJson();
    String runIdentity = runs.findByRunId(fixture.runId()).orElseThrow().getIdentityJson();
    String recordingHash = captures.findBySessionId(fixture.sessionId()).orElseThrow().getRecordingHash();

    leaseAgain(fixture.operation().getId(), "retry-exact");
    captureResults.applyComplete(fixture.operation().getId(), "retry-exact", success(fixture));

    RoutineCaptureRecord capture = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    assertEquals("SUCCEEDED", operations.findById(fixture.operation().getId()).orElseThrow().getState());
    assertEquals("COMPLETED", capture.getCompletionStatus());
    assertEquals(captureIdentity, capture.getIdentityJson());
    assertEquals(recordingHash, capture.getRecordingHash());
    assertEquals(runIdentity, runs.findByRunId(fixture.runId()).orElseThrow().getIdentityJson());
    assertEquals(1, bindings.findByRunId(fixture.runId()).stream().count());
  }

  @Test
  void differentCompletionIdentityCannotReuseAnExistingCompletion() {
    Fixture fixture = fixture("identity-reuse");
    captureResults.applyComplete(fixture.operation().getId(), fixture.attemptId(), success(fixture));
    RoutineCaptureRecord completed = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    String identity = completed.getIdentityJson();
    String recordingHash = completed.getRecordingHash();

    leaseAgain(fixture.operation().getId(), "retry-different");
    captureResults.applyComplete(fixture.operation().getId(), "retry-different",
        new CaptureRecordingGateway.CompleteResult(
            true, false, fixture.recordingId() + "-other", "C:\\capture\\other.mp4",
            OTHER_VIDEO_HASH, null));

    RoutineCaptureOperation operation = operations.findById(fixture.operation().getId()).orElseThrow();
    RoutineCaptureRecord after = captures.findBySessionId(fixture.sessionId()).orElseThrow();
    assertEquals("FAILED", operation.getState());
    assertEquals("COMPLETED", after.getCompletionStatus());
    assertTrue(after.isCompleted());
    assertEquals(identity, after.getIdentityJson());
    assertEquals(recordingHash, after.getRecordingHash());
    assertEquals(fixture.recordingId(), after.getRecordingId());
  }

  private Fixture fixture(String suffix) {
    return fixture(suffix, identity("capture-source-" + suffix));
  }

  private Fixture fixture(String suffix, CurrentSliceIdentity runIdentity) {
    String runId = runIdentity.run().runId();
    String sessionId = "capture-source-" + suffix + "-session";
    String attemptId = "capture-source-" + suffix + "-attempt";
    String recordingId = "capture-source-" + suffix + "-recording";

    CurrentSliceRun run = new CurrentSliceRun();
    run.setRunId(runId);
    run.setAttemptId("attempt-1");
    run.setIdentityJson(write(runIdentity));
    run.setStage("BOUND");
    runs.saveAndFlush(run);

    RoutineCaptureRecord capture = new RoutineCaptureRecord();
    capture.setSessionId(sessionId);
    capture.setStartIdempotencyKey(sessionId + "-start");
    capture.setStartRequestHash(HASH);
    capture.setIdentityJson(write(identityWithFixtureSource(runId)));
    capture.setStartedAt(java.time.Instant.now());
    capture.setStartTick(0);
    capture.setTicksJson("[]");
    capture.setActionsJson("[]");
    capture.setCompletionStatus("RECORDING");
    capture.setOperation("COMPLETE");
    capture.setOperationState("LEASED");
    capture.setDebugSessionId("capture-source-" + suffix + "-debug");
    captures.saveAndFlush(capture);

    RoutineCaptureOperation operation = new RoutineCaptureOperation();
    operation.setSessionId(sessionId);
    operation.setOperation("COMPLETE");
    operation.setRequestHash(HASH);
    operation.setRequestJson("{\"video_sha256\":\"" + VIDEO_HASH + "\",\"final_tick\":12}");
    operation.setCorrelationId("capture-source-" + suffix + "-correlation");
    operation.setState("LEASED");
    operation.setAttemptId(attemptId);
    operation.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(60));
    operation.setAttempts(1);
    operation.setNextAttemptAt(java.time.Instant.now());
    operations.saveAndFlush(operation);
    return new Fixture(runId, sessionId, recordingId, attemptId, operation);
  }

  private void persistDuplicateRecording(String runId) {
    RoutineCaptureRecord duplicate = new RoutineCaptureRecord();
    duplicate.setSessionId("capture-source-duplicate-existing");
    duplicate.setStartIdempotencyKey("capture-source-duplicate-start");
    duplicate.setStartRequestHash(HASH);
    duplicate.setIdentityJson(write(identity(runId + "-duplicate")));
    duplicate.setStartedAt(java.time.Instant.now());
    duplicate.setStartTick(0);
    duplicate.setTicksJson("[]");
    duplicate.setActionsJson("[]");
    duplicate.setCompletionStatus("COMPLETED");
    duplicate.setCompleted(true);
    duplicate.setRecordingId("capture-source-capture-update-failure-recording");
    captures.saveAndFlush(duplicate);
  }

  private SourceRunBinding persistConflictingBinding(String runId) {
    String hash = "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    String snapshot = "srcsnap_eeeeeeeeeeeeeeeeeeeeeeee";
    SourceManifestReference manifest = new SourceManifestReference();
    manifest.setTaskKey("EXAMPLE_TASK");
    manifest.setSchemaVersion(SourceManifestContract.SCHEMA);
    manifest.setManifestHash(hash);
    manifest.setSnapshotId(snapshot);
    manifest.setRepositoryId("micro6");
    manifest.setCommitId("0123456789abcdef0123456789abcdef01234567");
    manifest.setTreeId("fedcba9876543210fedcba9876543210fedcba98");
    manifest.setBuildId("micro6-build-other");
    manifest.setArtifactDigest(ARTIFACT);
    manifest.setVerificationState("VERIFIED");
    manifest.setSourceProvenance("VERIFIED");
    manifest.setRetentionClass("DEBUG");
    manifest.setConfidentiality("INTERNAL");
    manifest.setEnvironment("production");
    manifest.setManifestJson("{\"schema\":\"" + SourceManifestContract.SCHEMA + "\"}");
    manifests.saveAndFlush(manifest);

    SourceRunBinding binding = new SourceRunBinding();
    binding.setRunId(runId);
    binding.setTaskKey("EXAMPLE_TASK");
    binding.setManifestHash(hash);
    binding.setSnapshotId(snapshot);
    binding.setRepositoryId("micro6");
    binding.setCommitId(manifest.getCommitId());
    binding.setTreeId(manifest.getTreeId());
    binding.setBuildId(manifest.getBuildId());
    binding.setArtifactDigest(ARTIFACT);
    binding.setEnvironment("production");
    binding.setVerificationState("VERIFIED");
    binding.setSourceProvenance("VERIFIED");
    bindings.saveAndFlush(binding);
    return binding;
  }

  private void leaseAgain(Long operationId, String attemptId) {
    tx().executeWithoutResult(status -> {
      RoutineCaptureOperation operation = operations.findById(operationId).orElseThrow();
      operation.setState("LEASED");
      operation.setAttemptId(attemptId);
      operation.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(60));
      operations.saveAndFlush(operation);
    });
  }

  private CaptureRecordingGateway.CompleteResult success(Fixture fixture) {
    return new CaptureRecordingGateway.CompleteResult(
        true, false, fixture.recordingId(), "C:\\capture\\video.mp4", VIDEO_HASH, null);
  }

  private CurrentSliceIdentity identity(String runId) {
    return identity(runId, HASH);
  }

  private CurrentSliceIdentity identity(String runId, String settingsHash) {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", settingsHash, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", ARTIFACT,
            "micro6-builder/v2", "MICRO6"),
        null, null, null, null, null, false);
  }

  private CurrentSliceIdentity identityWithFixtureSource(String runId) {
    CurrentSliceIdentity base = identity(runId);
    var manifest = SourceManifestTestFixtures.manifest();
    return new CurrentSliceIdentity(
        base.schema(), base.task(), base.run(), base.client(), base.capture(),
        new CurrentSliceIdentity.Source(
            manifest.path("source_snapshot_id").asText(),
            manifest.path("source_manifest_hash").asText(),
            manifest.path("repository").path("repository_id").asText(),
            manifest.path("repository").path("commit_id").asText(),
            manifest.path("repository").path("tree_id").asText(),
            manifest.path("build").path("build_id").asText(),
            manifest.path("build").path("artifact_digest").asText(), true),
        base.graph(), base.model(), base.candidate(), base.controlAllowed());
  }

  private CurrentSliceIdentity identityWithSource(String runId) {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", ARTIFACT,
            "micro6-builder/v2", "MICRO6"),
        null,
        new CurrentSliceIdentity.Source(
            "srcsnap_eeeeeeeeeeeeeeeeeeeeeeee", HASH, "micro6",
            "0123456789abcdef0123456789abcdef01234567",
            "fedcba9876543210fedcba9876543210fedcba98", "micro6-build-other",
            OTHER_HASH, true),
        null, null, null, false);
  }

  private Optional<SourceManifestReference> findManifest(String hash) {
    return tx().execute(status -> manifests.findByManifestHash(hash));
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("capture fixture JSON could not be written", exception);
    }
  }

  private static KeyPair signingKey() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException("Ed25519 unavailable", exception);
    }
  }

  private record Fixture(
      String runId,
      String sessionId,
      String recordingId,
      String attemptId,
      RoutineCaptureOperation operation) {}

  private static final class FetchGate {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
  }
}
