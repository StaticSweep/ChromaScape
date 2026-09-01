package com.chromascape.web.replay.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.worker.ReplayWorker.ExecutionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * UNIT test of the Java orchestration inside {@link ReplayWorker#restoreFromCheckpoint}.
 *
 * <p>Despite its historical name, this is NOT an end-to-end branch claim: the kernel process,
 * artifact service, and every repository are MOCKED, and the "kernel responses" are stubs. What it
 * actually proves is the Java-side orchestration contract — canonical snapshot creation, fixed
 * content-address prefix handling, internal checksum verification ordering, post-restore
 * observation equality, and in-memory chain-hash lineage. It does NOT prove real artifact
 * publication/readback against storage, an actual xRSPS restore executing in a worker process,
 * Java/TypeScript checksum parity, continuation in a fresh OS process, or persisted child lineage.
 * Those properties belong to the Windows-only {@code replayCertificationTest} integration suite,
 * which drives a freshly built certifiable worker over PostgreSQL and real artifact storage.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayWorkerBranchRestoreTest {

  @Mock ReplayRunRepository runRepository;
  @Mock com.chromascape.web.replay.run.repository.ReplayAttemptRepository attemptRepository;
  @Mock ReplayRunService runService;

  @Mock
  com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository manifestRepository;

  @Mock ReplayCheckpointRepository checkpointRepository;
  @Mock ArtifactRefRepository artifactRefRepository;
  @Mock ReplayMismatchRepository mismatchRepository;
  @Mock ReplayWorkerPersistenceService persistence;
  @Mock ReplayLeaseHeartbeat heartbeatService;
  @Mock ReplayExpiredAttemptRecoveryService recoveryService;
  @Mock ReplayArtifactService artifactService;
  @Mock MismatchEngine mismatchEngine;
  @Mock ReplayReportService reportService;

  private final CanonicalProjectionService projectionService = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();

  private ReplayWorker worker;
  private XrspsKernelProcess kernel;

  private UUID parentRunId;
  private UUID childRunId;
  private UUID attemptId;
  private UUID checkpointId;

  private RuntimeIdentity liveIdentity;
  private ObjectNode observation;
  private String observationStateHash;
  private String parentChainHash;
  private ReplayCheckpoint checkpoint;
  private byte[] snapBytes;

  @BeforeEach
  void setUp() {
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
            heartbeatService,
            recoveryService,
            artifactService,
            mismatchEngine,
            reportService,
            projectionService,
            new ReplayProperties(),
            mapper);

    parentRunId = UUID.randomUUID();
    childRunId = UUID.randomUUID();
    attemptId = UUID.randomUUID();
    checkpointId = UUID.randomUUID();

    liveIdentity =
        new RuntimeIdentity(
            "adapter-under-test",
            "b".repeat(40),
            "sha256:" + "c".repeat(64),
            "sha256:" + "d".repeat(64));

    // Observation whose canonical hash anchors the checkpoint state.
    observation = mapper.createObjectNode();
    observation.putObject("player").put("x", 3210).put("y", 3211).put("plane", 0);
    observationStateHash = projectionService.stateHash(observation);
    parentChainHash =
        projectionService.chainHash(genesis(), digest("parent-action"), observationStateHash);

    snapBytes = canonicalSnapshotBytes(7L);

    ArtifactRef ref = finalizedSnapshotRef(snapBytes);

    checkpoint =
        new ReplayCheckpoint(
            checkpointId,
            parentRunId,
            attemptId,
            42L,
            snapshotInternalChecksum(),
            observationStateHash,
            parentChainHash,
            "main");
    checkpoint.setActionIndex(3);
    checkpoint.setSnapshotArtifactChecksum(ref.getChecksum());

    ReplayRun childRun = childBranchRun();

    lenient().when(checkpointRepository.findById(checkpointId)).thenReturn(Optional.of(checkpoint));
    lenient()
        .when(
            artifactRefRepository.findByRunIdAndKindAndChecksum(
                eq(parentRunId), eq(ArtifactKind.SNAPSHOT), anyString()))
        .thenReturn(Optional.of(ref));
    lenient().when(runRepository.findById(childRunId)).thenReturn(Optional.of(childRun));
    lenient()
        .when(artifactRefRepository.findByRunIdAndState(parentRunId, ArtifactRef.STATE_FINALIZED))
        .thenReturn(java.util.List.of(ref));
    // Deadline budget is always positive; deadline enforcement has its own dedicated tests.
    lenient()
        .when(runRepository.remainingDeadlineMs(eq(childRunId), any(long.class)))
        .thenReturn(60_000L);
    lenient()
        .when(runRepository.isDeadlineExceeded(eq(childRunId), any(long.class)))
        .thenReturn(false);
  }

  /** Steps 1-2 of the contract: produce exactly what checkpoint creation would have stored. */
  private byte[] canonicalSnapshotBytes(long nextOrdinal) {
    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.put("snapshotVersion", 1);
    snapshot
        .putObject("runtimeIdentity")
        .put("xrspsAdapterVersion", liveIdentity.xrspsAdapterVersion())
        .put("xrspsBuildCommit", liveIdentity.xrspsBuildCommit())
        .put("cacheRevision", liveIdentity.cacheRevision())
        .put("contentRevision", liveIdentity.contentRevision());
    snapshot.putObject("eventQueue").put("nextOrdinal", nextOrdinal);
    snapshot.set("observation", observation.deepCopy());
    // Internal checksum over the canonical document with `checksum` omitted — the exact rule the
    // worker applies during verification and the kernel applies at creation time.
    ObjectNode withoutChecksum = snapshot.deepCopy();
    withoutChecksum.remove("checksum");
    String internal =
        projectionService.sha256Hex(projectionService.canonicalJsonBytes(withoutChecksum));
    snapshot.put("checksum", internal);
    return projectionService.canonicalJsonBytes(snapshot);
  }

  private String snapshotInternalChecksum() {
    try {
      JsonNode doc = mapper.readTree(snapBytes);
      ObjectNode withoutChecksum = doc.deepCopy();
      withoutChecksum.remove("checksum");
      return projectionService.sha256Hex(projectionService.canonicalJsonBytes(withoutChecksum));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Published SNAPSHOT artifact content address over the complete serialized bytes. */
  private ArtifactRef finalizedSnapshotRef(byte[] bytes) {
    String contentAddress = projectionService.sha256Hex(bytes);
    ArtifactRef ref =
        new ArtifactRef(
            UUID.randomUUID(), parentRunId, ArtifactKind.SNAPSHOT, contentAddress, bytes.length);
    ref.setMediaType("application/json");
    ref.setState(ArtifactRef.STATE_FINALIZED);
    return ref;
  }

  private ReplayRun childBranchRun() {
    ReplayRun run = new ReplayRun();
    run.setId(childRunId);
    run.setParentRunId(parentRunId);
    run.setParentCheckpointId(checkpointId);
    run.setBranchKey("branch-" + childRunId);
    return run;
  }

  private String genesis() {
    return projectionService.sha256Hex(
        "chromascape-replay-chain-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String digest(String label) {
    return projectionService.sha256Hex(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private KernelMessage ok(JsonNode result) {
    return new KernelMessage("resp", null, result, null, false, false);
  }

  private ReplayFence fence() {
    return new ReplayFence(childRunId, attemptId, 1, 1, "worker-test");
  }

  /** Healthy heartbeat handle: assertHealthy() is a no-op in this slice. */
  private ReplayLeaseHeartbeat.Handle heartbeatHandle() {
    ReplayLeaseHeartbeat service = mock(ReplayLeaseHeartbeat.class);
    ReplayLeaseHeartbeat.Handle handle = mock(ReplayLeaseHeartbeat.Handle.class);
    lenient().when(service.start(any())).thenReturn(handle);
    doNothing().when(handle).assertHealthy();
    return handle;
  }

  // ── the real branch replay flow ──

  @Test
  void publishedCheckpointRestoresContinuesAndPreservesChainLineage() throws Exception {
    kernel = mock(XrspsKernelProcess.class);

    // Step 4: the worker reads and hashes the STORED bytes through the artifact service.
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes);

    // Step 6: fresh kernel answers restore landing exactly on the checkpoint tick…
    ObjectNode restoreResult = mapper.createObjectNode().put("restoredTick", 42L);
    when(kernel.send(eq("restore"), any(JsonNode.class), anyLong())).thenReturn(ok(restoreResult));
    // …and step 7: post-restore observe (null params!) reproduces the checkpoint state hash.
    when(kernel.send(eq("observe"), isNull(), anyLong()))
        .thenReturn(ok(mapper.createObjectNode().set("observation", observation.deepCopy())));

    ExecutionState es = new ExecutionState(genesis());

    ReplayWorker.RestoredCheckpoint restored =
        worker.restoreFromCheckpoint(
            heartbeatHandle(),
            kernel,
            runRepository.findById(childRunId).orElseThrow(),
            mapper.createObjectNode(),
            liveIdentity,
            es);

    assertEquals(42L, restored.tick());
    assertEquals(3, restored.actionIndex());

    // Chain lineage: execution continues from the PARENT chain hash, never from genesis.
    assertEquals(parentChainHash, es.chainHash, "child must inherit the parent chain hash");
    assertNotEquals(genesis(), es.chainHash);

    // Step 8: continue execution one action past the checkpoint and prove the chain derives
    // from the inherited parent hash.
    JsonNode action = mapper.createObjectNode().put("type", "INTERACT_NPC");
    String newStateHash = projectionService.stateHash(observation);
    es.chainHash =
        projectionService.chainHash(
            es.chainHash, projectionService.actionDigest(action), newStateHash);
    String expectedChildHash =
        projectionService.chainHash(
            parentChainHash, projectionService.actionDigest(action), newStateHash);
    assertEquals(expectedChildHash, es.chainHash, "continued chain must build on the parent hash");
    assertNotNull(es.lastStateHash);
    assertEquals(observationStateHash, es.lastStateHash);
  }

  @Test
  void tamperedStoredSnapshotBytesFailContentAddressVerification() throws Exception {
    byte[] tampered = snapBytes.clone();
    tampered[tampered.length / 2] ^= 0x01;
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(tampered);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    mapper.createObjectNode(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
  }

  @Test
  void nullParentRunIdFailsClosedBeforeCheckpointLookup() {
    ReplayRun run = childBranchRun();
    run.setParentRunId(null);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e = assertRestoreFails(run, liveIdentity);

    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
  }

  @Test
  void nullParentCheckpointIdFailsClosedBeforeCheckpointLookup() {
    ReplayRun run = childBranchRun();
    run.setParentCheckpointId(null);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e = assertRestoreFails(run, liveIdentity);

    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
  }

  @Test
  void foreignCheckpointFailsClosed() {
    UUID foreignRunId = UUID.randomUUID();
    ReplayCheckpoint foreign =
        new ReplayCheckpoint(
            checkpointId,
            foreignRunId,
            attemptId,
            checkpoint.getTick(),
            checkpoint.getSnapshotChecksum(),
            checkpoint.getStateHash(),
            checkpoint.getChainHash(),
            checkpoint.getBranchKey());
    foreign.setActionIndex(checkpoint.getActionIndex());
    foreign.setSnapshotArtifactChecksum(checkpoint.getSnapshotArtifactChecksum());
    when(checkpointRepository.findById(checkpointId)).thenReturn(Optional.of(foreign));
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e = assertRestoreFails(childBranchRun(), liveIdentity);

    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
  }

  @Test
  void missingSnapshotArtifactChecksumFailsClosed() {
    checkpoint.setSnapshotArtifactChecksum(null);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e = assertRestoreFails(childBranchRun(), liveIdentity);

    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
  }

  @Test
  void snapshotRuntimeIdentityMismatchFailsClosed() throws Exception {
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes);
    kernel = mock(XrspsKernelProcess.class);
    RuntimeIdentity foreignIdentity =
        new RuntimeIdentity(
            "foreign-adapter",
            liveIdentity.xrspsBuildCommit(),
            liveIdentity.cacheRevision(),
            liveIdentity.contentRevision());

    ReplayException e = assertRestoreFails(childBranchRun(), foreignIdentity);

    assertEquals(ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE, e.getErrorCode());
  }

  @Test
  void internalChecksumDriftBetweenRowAndSnapshotFailsClosed() throws Exception {
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes);
    checkpoint.setSnapshotChecksum("sha256:" + "9".repeat(64));
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    mapper.createObjectNode(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
  }

  private ReplayException assertRestoreFails(ReplayRun run, RuntimeIdentity identity) {
    return assertThrows(
        ReplayException.class,
        () ->
            worker.restoreFromCheckpoint(
                heartbeatHandle(),
                kernel,
                run,
                mapper.createObjectNode(),
                identity,
                new ExecutionState(genesis())));
  }
}
