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
import com.chromascape.web.replay.hash.SemanticHashV2;
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

    ArtifactRef ref = finalizedRef(snapBytes, ArtifactKind.SNAPSHOT);

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

    // SC-275 bound restore evidence: context plus finalized CHAIN artifact.
    String runtimeDigest =
        SemanticHashV2.runtimeDigest(
            liveIdentity.xrspsAdapterVersion(),
            liveIdentity.xrspsBuildCommit(),
            liveIdentity.cacheRevision(),
            liveIdentity.contentRevision());
    String genesis = SemanticHashV2.genesisHash(MANIFEST_DIGEST, runtimeDigest);
    ObjectNode resetFrame = v2Frame(0, 0, 0, "RESET", "ABSTAINED", "no-action", genesis);
    ObjectNode firstFrame =
        v2Frame(1, 1, 0, "ACTION", "ACCEPTED", "applied", resetFrame.path("chainHash").asText());
    java.util.List<JsonNode> prefixFrames = java.util.List.of(resetFrame, firstFrame);
    ObjectNode context = mapper.createObjectNode();
    context.put("contextVersion", 2);
    context.put("manifestDigest", MANIFEST_DIGEST);
    context.put("runtimeDigest", runtimeDigest);
    context.put("snapshotVersion", 1);
    context.put("snapshotDigest", projectionService.sha256Hex(snapBytes));
    context.put("checkpointStateDigest", digest("checkpoint-state"));
    context.put("tick", 42);
    context.put("nextTransitionOrdinal", prefixFrames.size());
    context.put("predecessorChainHash", firstFrame.path("chainHash").asText());
    checkpoint.setRestoreContext(canonical(context));
    chainBytes =
        projectionService.canonicalJsonBytes(mapper.createArrayNode().addAll(prefixFrames));
    ArtifactRef chainRef = finalizedRef(chainBytes, ArtifactKind.CHAIN);
    checkpoint.setChainArtifactChecksum(chainRef.getChecksum());
    lenient()
        .when(
            artifactRefRepository.findByRunIdAndKindAndChecksum(
                eq(parentRunId), eq(ArtifactKind.CHAIN), anyString()))
        .thenReturn(Optional.of(chainRef));

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
    snapshot.put("tick", 42L);
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

  private static final String MANIFEST_DIGEST = "sha256:" + "ab".repeat(32);

  private byte[] chainBytes;

  /** SC-275 v2 transition frame with a recomputed chain hash. */
  private ObjectNode v2Frame(
      int ordinal,
      long tick,
      long sequence,
      String kind,
      String result,
      String message,
      String previous) {
    ObjectNode frame = mapper.createObjectNode();
    frame.put("traceVersion", 2);
    frame.put("manifestDigest", MANIFEST_DIGEST);
    String runtimeDigest =
        SemanticHashV2.runtimeDigest(
            liveIdentity.xrspsAdapterVersion(),
            liveIdentity.xrspsBuildCommit(),
            liveIdentity.cacheRevision(),
            liveIdentity.contentRevision());
    frame.put("runtimeDigest", runtimeDigest);
    frame.put("tick", tick);
    frame.put("sequence", sequence);
    frame.put("ordinal", ordinal);
    frame.put("kind", kind);
    String actionDigest = SemanticHashV2.nullActionDigest();
    String resultDigest = SemanticHashV2.resultDigest(result, message);
    frame.put("actionDigest", actionDigest);
    frame.put("resultDigest", resultDigest);
    frame.put("stateHash", observationStateHash);
    frame.put("previousChainHash", previous);
    frame.put(
        "chainHash",
        SemanticHashV2.chainHash(
            MANIFEST_DIGEST,
            runtimeDigest,
            previous,
            tick,
            sequence,
            ordinal,
            kind,
            actionDigest,
            resultDigest,
            observationStateHash));
    return frame;
  }

  private String canonical(JsonNode node) {
    try {
      return new String(
          projectionService.canonicalJsonBytes(node), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ObjectNode manifestDoc() {
    return mapper.createObjectNode().put("manifestChecksum", MANIFEST_DIGEST);
  }

  /** Published artifact content address over the complete serialized bytes. */
  private ArtifactRef finalizedRef(byte[] bytes, ArtifactKind kind) {
    String contentAddress = projectionService.sha256Hex(bytes);
    ArtifactRef ref =
        new ArtifactRef(UUID.randomUUID(), parentRunId, kind, contentAddress, bytes.length);
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

    // Step 4: the worker reads and hashes the STORED bytes through the artifact service —
    // snapshot bytes first, then the bound chain artifact.
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes, chainBytes);

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
            manifestDoc(),
            liveIdentity,
            es);

    assertEquals(42L, restored.tick());
    assertEquals(3, restored.actionIndex());

    // The worker restore carries the bound context, prefix frames, and manifest.
    org.mockito.ArgumentCaptor<JsonNode> restoreParams =
        org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    org.mockito.Mockito.verify(kernel).send(eq("restore"), restoreParams.capture(), anyLong());
    assertNotNull(restoreParams.getValue().path("context"));
    assertEquals(2, restoreParams.getValue().path("context").path("contextVersion").asInt());
    assertEquals(2, restoreParams.getValue().path("priorFrames").size());
    assertEquals(
        MANIFEST_DIGEST,
        restoreParams.getValue().path("manifest").path("manifestChecksum").asText());

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

  @Test
  void legacyCheckpointWithoutBindingsFailsClosedBeforeKernelRestore() throws Exception {
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes);
    checkpoint.setRestoreContext(null);
    checkpoint.setChainArtifactChecksum(null);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    manifestDoc(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
    // The kernel restore is never reached for unbound historical rows.
    org.mockito.Mockito.verify(kernel, org.mockito.Mockito.never())
        .send(eq("restore"), any(JsonNode.class), anyLong());
  }

  @Test
  void substitutedRestoreContextFailsContentAddress() throws Exception {
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes, chainBytes);
    ObjectNode substituted;
    try {
      substituted = (ObjectNode) mapper.readTree(checkpoint.getRestoreContext());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    substituted.put("manifestDigest", "sha256:" + "ee".repeat(32));
    checkpoint.setRestoreContext(canonical(substituted));
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    manifestDoc(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
  }

  @Test
  void tamperedChainBytesFailContentAddressVerification() throws Exception {
    byte[] tampered = chainBytes.clone();
    tampered[tampered.length / 2] ^= 0x01;
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes, tampered);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    manifestDoc(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
    org.mockito.Mockito.verify(kernel, org.mockito.Mockito.never())
        .send(eq("restore"), any(JsonNode.class), anyLong());
  }

  @Test
  void chainOrdinalMismatchFailsClosed() throws Exception {
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes, chainBytes);
    ObjectNode resized;
    try {
      resized = (ObjectNode) mapper.readTree(checkpoint.getRestoreContext());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    resized.put("nextTransitionOrdinal", resized.path("nextTransitionOrdinal").asInt(2) + 1);
    checkpoint.setRestoreContext(canonical(resized));
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    manifestDoc(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
    org.mockito.Mockito.verify(kernel, org.mockito.Mockito.never())
        .send(eq("restore"), any(JsonNode.class), anyLong());
  }

  @Test
  void tamperedChainHistoryFailsAnchoredVerification() throws Exception {
    com.fasterxml.jackson.databind.node.ArrayNode frames;
    try {
      frames = (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(chainBytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    ObjectNode second = (ObjectNode) frames.get(1).deepCopy();
    second.put("stateHash", "sha256:" + "dd".repeat(32));
    frames.set(1, second);
    byte[] tamperedBytes = projectionService.canonicalJsonBytes(frames);
    ArtifactRef tamperedRef = finalizedRef(tamperedBytes, ArtifactKind.CHAIN);
    checkpoint.setChainArtifactChecksum(tamperedRef.getChecksum());
    lenient()
        .when(
            artifactRefRepository.findByRunIdAndKindAndChecksum(
                eq(parentRunId), eq(ArtifactKind.CHAIN), anyString()))
        .thenReturn(Optional.of(tamperedRef));
    when(artifactService.readFinalized(any(ArtifactRef.class)))
        .thenReturn(snapBytes, tamperedBytes);
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    manifestDoc(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
    org.mockito.Mockito.verify(kernel, org.mockito.Mockito.never())
        .send(eq("restore"), any(JsonNode.class), anyLong());
  }

  @Test
  void missingChainReferenceFailsClosed() throws Exception {
    when(artifactService.readFinalized(any(ArtifactRef.class))).thenReturn(snapBytes);
    lenient()
        .when(
            artifactRefRepository.findByRunIdAndKindAndChecksum(
                eq(parentRunId), eq(ArtifactKind.CHAIN), anyString()))
        .thenReturn(Optional.empty());
    kernel = mock(XrspsKernelProcess.class);

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                worker.restoreFromCheckpoint(
                    heartbeatHandle(),
                    kernel,
                    runRepository.findById(childRunId).orElseThrow(),
                    manifestDoc(),
                    liveIdentity,
                    new ExecutionState(genesis())));
    assertEquals(ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, e.getErrorCode());
    org.mockito.Mockito.verify(kernel, org.mockito.Mockito.never())
        .send(eq("restore"), any(JsonNode.class), anyLong());
  }

  private JsonNode realWorkerEvidence() throws Exception {
    try (var input =
        getClass().getResourceAsStream("/replay/contracts/sc275-worker-evidence.json")) {
      return mapper.readTree(input);
    }
  }

  @Test
  void actualWorkerSamplesVerifyAndPersistV2WithSameTickRejectionAndTerminal() throws Exception {
    JsonNode fixture = realWorkerEvidence();
    JsonNode context = fixture.path("snapshot").path("context");
    ExecutionState es =
        new ExecutionState(
            SemanticHashV2.genesisHash(
                context.path("manifestDigest").asText(), context.path("runtimeDigest").asText()));
    es.causal = true;
    es.manifestDigest = context.path("manifestDigest").asText();
    es.runtimeDigest = context.path("runtimeDigest").asText();
    int ordinal = 0;
    for (String response : java.util.List.of("reset", "step", "rejected", "terminal")) {
      var sample = worker.consumeCausalEvidence(childRunId, fixture.path(response), es);
      assertEquals(2, sample.getProjectionVersion());
      assertEquals((long) ordinal++, sample.getTransitionOrdinal());
      assertEquals(
          fixture.path(response).path("causalSample").path("stateHash").asText(),
          sample.getStateHash());
    }
    assertEquals(true, es.causalTerminated);
    assertThrows(
        ReplayException.class,
        () -> worker.consumeCausalEvidence(childRunId, fixture.path("step"), es));
  }

  @Test
  void actualTakeCheckpointPersistsBoundSnapshotChainAndProjection() throws Exception {
    JsonNode fixture = realWorkerEvidence();
    JsonNode snapshotResponse = fixture.path("snapshot");
    JsonNode context = snapshotResponse.path("context");
    var identity = fixture.path("identity");
    RuntimeIdentity live =
        new RuntimeIdentity(
            identity.path("adapterVersion").asText(),
            identity.path("buildCommit").asText(),
            identity.path("cacheRevision").asText(),
            identity.path("contentRevision").asText());
    ExecutionState es = new ExecutionState(context.path("predecessorChainHash").asText());
    es.causal = true;
    var run = runRepository.findById(childRunId).orElseThrow();
    kernel = mock(XrspsKernelProcess.class);
    when(kernel.send(eq("snapshot"), isNull(), anyLong())).thenReturn(ok(snapshotResponse));
    when(artifactService.prepareArtifact(
            eq(childRunId), any(), any(byte[].class), eq("application/json")))
        .thenAnswer(
            call -> {
              byte[] bytes = call.getArgument(2);
              return new com.chromascape.web.replay.artifact.PreparedArtifact(
                  UUID.randomUUID(),
                  childRunId,
                  call.getArgument(1),
                  projectionService.sha256Hex(bytes),
                  bytes.length,
                  "application/json",
                  java.nio.file.Path.of("staged"));
            });
    when(persistence.registerCheckpoint(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              ReplayCheckpoint stored = call.getArgument(3);
              assertEquals(context.path("checkpointStateDigest").asText(), stored.getStateHash());
              assertEquals(context, mapper.readTree(stored.getRestoreContext()));
              return new com.chromascape.web.replay.artifact.ArtifactRegistration(
                  finalizedRef(new byte[] {1}, ArtifactKind.SNAPSHOT), true);
            });
    when(persistence.registerArtifact(any(), any()))
        .thenReturn(
            new com.chromascape.web.replay.artifact.ArtifactRegistration(
                finalizedRef(new byte[] {2}, ArtifactKind.PROJECTION), true));
    worker.takeCheckpoint(
        kernel, heartbeatHandle(), fence(), run, 1, 0, es, fixture.path("manifest"), live);
    assertEquals(1, es.checkpointCount);
    org.mockito.Mockito.verify(artifactService)
        .prepareArtifact(
            eq(childRunId), eq(ArtifactKind.CHAIN), any(byte[].class), eq("application/json"));
    org.mockito.Mockito.verify(artifactService)
        .prepareArtifact(
            eq(childRunId), eq(ArtifactKind.PROJECTION), any(byte[].class), eq("application/json"));
    ObjectNode corrupted = snapshotResponse.deepCopy();
    ((ObjectNode) corrupted.path("context")).put("snapshotDigest", digest("wrong"));
    when(kernel.send(eq("snapshot"), isNull(), anyLong())).thenReturn(ok(corrupted));
    org.mockito.Mockito.clearInvocations(artifactService);
    assertThrows(
        ReplayException.class,
        () ->
            worker.takeCheckpoint(
                kernel, heartbeatHandle(), fence(), run, 1, 0, es, fixture.path("manifest"), live));
    org.mockito.Mockito.verifyNoInteractions(artifactService);
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
