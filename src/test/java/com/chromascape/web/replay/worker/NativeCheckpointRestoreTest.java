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
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.*;
import com.chromascape.web.replay.run.entity.*;
import com.chromascape.web.replay.run.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Production Java registration/restore; controlled transport and in-memory repositories, NOT DB
 * durability.
 */
class NativeCheckpointRestoreTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final CanonicalProjectionService HASHES = new CanonicalProjectionService();
  static final RuntimeIdentity RUNTIME = new RuntimeIdentity("0.1.0", "build", "cache", "content");

  static ObjectNode manifest() {
    ObjectNode m =
        JSON.createObjectNode()
            .put("manifestVersion", 2)
            .put("manifestChecksum", "sha256:" + "a".repeat(64));
    m.set("runtime", JSON.valueToTree(RUNTIME));
    m.putObject("recording").put("startTick", 0).put("endTick", 4);
    m.putObject("execution")
        .put("schema", "xrsps-execution-limits/v1")
        .put("maximumTicks", 4)
        .put("maximumActions", 10)
        .put("maximumSteps", 10);
    var actions = m.putArray("actions");
    for (int tick : List.of(0, 3))
      actions
          .addObject()
          .put("type", "WAIT_UNTIL_TICK")
          .put("tick", tick)
          .put("sourceActionTick", tick)
          .put("sourceActionSequence", 0);
    return m;
  }

  static ObjectNode state(ObjectNode m, int tick, int actions, int steps) {
    ObjectNode s =
        JSON.createObjectNode()
            .put("schema", NativeRunLedger.SCHEMA)
            .put("status", "RUNNING")
            .putNull("reason")
            .putNull("outcome")
            .putNull("conditionId")
            .putNull("terminalTick")
            .put("startTick", 0)
            .put("tick", tick)
            .put("elapsedTicks", tick)
            .put("acceptedActions", actions)
            .put("acceptedSteps", steps);
    s.set("limits", m.path("execution").deepCopy());
    return s;
  }

  static ObjectNode result(ObjectNode state, boolean action) {
    ObjectNode r =
        JSON.createObjectNode()
            .put("terminalTick", state.path("tick").asLong())
            .put("actionApplied", action);
    r.set("runState", state);
    r.putObject("observation").put("tick", state.path("tick").asLong());
    r.putArray("events");
    return r;
  }

  static ReplayWorker.ExecutionState prefix(ObjectNode m, boolean terminal) {
    var es = new ReplayWorker.ExecutionState("sha256:" + "b".repeat(64));
    es.nativeLedger = new NativeRunLedger(m);
    es.nativeLedger.acceptReset(
        JSON.createObjectNode().put("initialTick", 0).set("runState", state(m, 0, 0, 0)));
    ObjectNode s = state(m, 0, 1, 1);
    if (terminal) {
      ((ObjectNode) m.path("execution")).put("maximumActions", 1);
      // Rebuild against the actual immutable limit, not a changed running ledger.
      es.nativeLedger = new NativeRunLedger(m);
      es.nativeLedger.acceptReset(
          JSON.createObjectNode().put("initialTick", 0).set("runState", state(m, 0, 0, 0)));
      s =
          state(m, 0, 1, 1)
              .put("status", "TERMINAL")
              .put("reason", "ACTION_LIMIT")
              .put("outcome", "INCONCLUSIVE")
              .put("terminalTick", 0);
    }
    es.nativeLedger.acceptStep(new ReplaySourceSchedule(m).next(0, 0, 100), result(s, true));
    es.executedActions = 1;
    es.lastStateHash = HASHES.stateHash(result(s, true).path("observation"));
    es.lastSampleTick = 0;
    es.lastSampleSequence = 0;
    return es;
  }

  static ObjectNode snapshot(ObjectNode manifest, ReplayWorker.ExecutionState es) {
    long tick = es.nativeLedger.state().path("tick").asLong();
    ObjectNode s =
        JSON.createObjectNode()
            .put("snapshotVersion", 1)
            .put("tick", tick)
            .put("canonicalStateHash", es.lastStateHash);
    s.set("runtimeIdentity", manifest.path("runtime").deepCopy());
    s.putObject("eventQueue").put("nextOrdinal", 7).putArray("events");
    ObjectNode d =
        s.putArray("contentState")
            .addObject()
            .put("kind", "authoritative-reconstruction-v1")
            .put("descriptorVersion", 4)
            .put("runEventSchema", 2)
            .put("resetMode", "offline")
            .put("tick", tick)
            .put("manifestChecksum", manifest.path("manifestChecksum").asText())
            .put("observationHash", es.lastStateHash);
    d.set("manifest", manifest.deepCopy());
    d.set("runState", es.nativeLedger.state());
    d.set("steps", es.nativeLedger.prefix());
    s.put("checksum", HASHES.stateHash(s));
    return s;
  }

  /**
   * Shared with actual guarded process lane; storage/terminalization remain explicit in-memory
   * stubs.
   */
  static final class Fixture implements AutoCloseable {
    final ObjectNode manifest;
    final ReplayRunRepository runs = mock(ReplayRunRepository.class);
    final ReplayCheckpointRepository checkpoints = mock(ReplayCheckpointRepository.class);
    final ArtifactRefRepository refs = mock(ArtifactRefRepository.class);
    final ReplayWorkerPersistenceService persistence = mock(ReplayWorkerPersistenceService.class);
    final ReplayArtifactService artifacts = mock(ReplayArtifactService.class);
    final XrspsKernelProcess kernel = mock(XrspsKernelProcess.class);
    final ReplayLeaseHeartbeat.Handle heartbeat = mock(ReplayLeaseHeartbeat.Handle.class);
    final ReplayProperties properties = new ReplayProperties();
    final ReplayRun parent = new ReplayRun();
    final ReplayFence fence =
        new ReplayFence(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "native-restore-test");
    final Map<String, byte[]> blobs = new HashMap<>();
    final ReplayWorker worker;
    ReplayCheckpoint checkpoint;
    ArtifactRef ref;

    Fixture(ObjectNode manifest) {
      this.manifest = manifest;
      parent.setId(fence.runId());
      parent.setOwnerId("owner");
      parent.setManifestId(UUID.randomUUID());
      parent.setManifestChecksum(manifest.path("manifestChecksum").asText());
      parent.setState(ReplayRunState.RUNNING);
      when(runs.findById(parent.getId())).thenReturn(Optional.of(parent));
      when(runs.remainingDeadlineMs(any(), anyLong())).thenReturn(60_000L);
      when(artifacts.prepareArtifact(
              any(), eq(ArtifactKind.SNAPSHOT), any(), eq("application/json")))
          .thenAnswer(
              i -> {
                byte[] bytes = i.getArgument(2);
                String address = HASHES.sha256Hex(bytes);
                blobs.put(address, bytes.clone());
                return new PreparedArtifact(
                    UUID.randomUUID(),
                    i.getArgument(0),
                    ArtifactKind.SNAPSHOT,
                    address,
                    bytes.length,
                    "application/json",
                    Path.of("in-memory-test-only"));
              });
      when(persistence.registerCheckpoint(any(), any(), isNull(), any()))
          .thenAnswer(
              i -> {
                PreparedArtifact prepared = i.getArgument(1);
                checkpoint = i.getArgument(3);
                checkpoint.setSnapshotArtifactChecksum(prepared.checksum());
                ref =
                    new ArtifactRef(
                        UUID.randomUUID(),
                        prepared.runId(),
                        ArtifactKind.SNAPSHOT,
                        prepared.checksum(),
                        prepared.sizeBytes());
                when(checkpoints.findById(checkpoint.getId())).thenReturn(Optional.of(checkpoint));
                when(refs.findByRunIdAndKindAndChecksum(
                        prepared.runId(), ArtifactKind.SNAPSHOT, prepared.checksum()))
                    .thenReturn(Optional.of(ref));
                return new ArtifactRegistration(ref, true);
              });
      when(artifacts.readFinalized(any()))
          .thenAnswer(i -> blobs.get(((ArtifactRef) i.getArgument(0)).getChecksum()));
      worker =
          new ReplayWorker(
              runs,
              mock(ReplayAttemptRepository.class),
              mock(ReplayRunService.class),
              mock(ScenarioManifestRepository.class),
              checkpoints,
              refs,
              mock(ReplayMismatchRepository.class),
              persistence,
              mock(ReplayLeaseHeartbeat.class),
              mock(ReplayExpiredAttemptRecoveryService.class),
              artifacts,
              mock(MismatchEngine.class),
              mock(ReplayReportService.class),
              HASHES,
              properties,
              JSON);
    }

    RuntimeIdentity runtime() {
      JsonNode r = manifest.path("runtime");
      return new RuntimeIdentity(
          r.path("xrspsAdapterVersion").asText(),
          r.path("xrspsBuildCommit").asText(),
          r.path("cacheRevision").asText(),
          r.path("contentRevision").asText());
    }

    ReplayRun child() {
      // This simulates finalization only; it does NOT exercise the fenced DB terminalization path.
      parent.setState(ReplayRunState.SUCCEEDED);
      ref.setState(ArtifactRef.STATE_FINALIZED);
      ReplayRun c = new ReplayRun();
      c.setId(UUID.randomUUID());
      c.setOwnerId(parent.getOwnerId());
      c.setState(ReplayRunState.RUNNING);
      c.setManifestId(parent.getManifestId());
      c.setManifestChecksum(parent.getManifestChecksum());
      c.setParentRunId(parent.getId());
      c.setParentCheckpointId(checkpoint.getId());
      when(runs.findById(c.getId())).thenReturn(Optional.of(c));
      return c;
    }

    void record(ReplayWorker.ExecutionState es, ObjectNode snapshot) {
      when(kernel.send(eq("snapshot"), isNull(), anyLong()))
          .thenReturn(message(JSON.createObjectNode().set("snapshot", snapshot)));
      worker.takeCheckpoint(
          kernel,
          heartbeat,
          fence,
          parent,
          es.nativeLedger.state().path("tick").asLong(),
          es.executedActions - 1,
          es,
          manifest,
          runtime());
    }

    void restoreReplies(ObjectNode snapshot, ReplayWorker.ExecutionState es) {
      ObjectNode reply =
          JSON.createObjectNode().put("restoredTick", snapshot.path("tick").asLong());
      reply.set("runState", es.nativeLedger.state());
      reply.putObject("observation").put("tick", snapshot.path("tick").asLong());
      when(kernel.send(eq("restore"), any(), anyLong())).thenReturn(message(reply));
      when(kernel.send(eq("observe"), isNull(), anyLong()))
          .thenReturn(
              message(JSON.createObjectNode().set("observation", reply.path("observation"))));
      when(kernel.send(eq("snapshot"), isNull(), anyLong()))
          .thenReturn(message(JSON.createObjectNode().set("snapshot", snapshot)));
    }

    @Override
    public void close() {
      worker.close();
    }
  }

  static KernelMessage message(JsonNode result) {
    return new KernelMessage("test", null, result, null, false, false);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "source-index",
        "source-action",
        "tick",
        "bound",
        "advance",
        "counter",
        "terminal",
        "mode",
        "missing-receipt-field"
      })
  void structuralRestoreCannotInventOrReorderSourceReceipts(String mutation) {
    ObjectNode m = manifest();
    var es = prefix(m, false);
    ObjectNode snap = snapshot(m, es);
    ObjectNode d = (ObjectNode) snap.path("contentState").get(0);
    ObjectNode receipt = (ObjectNode) d.path("steps").get(0);
    switch (mutation) {
      case "duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) d.path("steps"))
          .add(receipt.deepCopy());
      case "source-index" -> receipt.put("sourceActionIndex", 1);
      case "source-action" -> ((ObjectNode) receipt.path("action")).put("tick", 99);
      case "tick" -> receipt.put("expectedTick", 1);
      case "bound" -> receipt.put("maxTicks", 1);
      case "advance" -> receipt.put("terminalTick", 1);
      case "counter" -> ((ObjectNode) d.path("runState")).put("acceptedSteps", 2);
      case "terminal" -> ((ObjectNode) d.path("runState")).put("status", "TERMINAL");
      case "mode" -> d.put("resetMode", "live");
      case "missing-receipt-field" -> receipt.remove("action");
    }
    assertThatThrownBy(() -> NativeRunLedger.fromAnchoredSnapshot(m, snap))
        .isInstanceOf(ReplayException.class);
  }

  @Test
  void structuralNativeBoundSupportsOneHundredThousandStepsWithoutCausalNodeLimit() {
    ObjectNode m = manifest();
    m.putObject("recording").put("startTick", 0).put("endTick", 100_000);
    ((ObjectNode) m.path("execution")).put("maximumTicks", 100_000).put("maximumSteps", 100_000);
    m.putArray("actions");
    var es = new ReplayWorker.ExecutionState("unused");
    es.nativeLedger = new NativeRunLedger(m);
    es.nativeLedger.acceptReset(
        JSON.createObjectNode().put("initialTick", 0).set("runState", state(m, 0, 0, 0)));
    es.lastStateHash = "sha256:" + "b".repeat(64);
    ObjectNode snap = snapshot(m, es).put("tick", 100_000);
    ObjectNode d = (ObjectNode) snap.path("contentState").get(0);
    d.put("tick", 100_000);
    d.set(
        "runState",
        state(m, 100_000, 0, 100_000)
            .put("status", "TERMINAL")
            .put("reason", "STEP_LIMIT")
            .put("outcome", "INCONCLUSIVE")
            .put("terminalTick", 100_000));
    var steps = d.putArray("steps");
    for (int tick = 0; tick < 100_000; tick++)
      steps
          .addObject()
          .put("expectedTick", tick)
          .put("maxTicks", 1)
          .put("terminalTick", tick + 1)
          .putNull("action")
          .putNull("sourceActionIndex");
    NativeRunLedger restored = NativeRunLedger.fromAnchoredSnapshot(m, snap);
    assertThat(restored.state().path("acceptedSteps").asInt()).isEqualTo(100_000);
    assertThat(restored.terminal()).isTrue();
    steps.addNull();
    assertThatThrownBy(() -> NativeRunLedger.fromAnchoredSnapshot(m, snap))
        .hasMessageContaining("step bound");
  }

  @Test
  void productionWriteAndRestoreBindConsumedPrefixAndResumeSampleAndEventLineage()
      throws Exception {
    ObjectNode m = manifest();
    var es = prefix(m, false);
    ObjectNode snap = snapshot(m, es);
    try (var f = new Fixture(m)) {
      f.record(es, snap);
      ReplayRun child = f.child();
      f.restoreReplies(snap, es);
      assertThat(JSON.readTree(f.checkpoint.getRestoreContext()).path("schema").asText())
          .isEqualTo(NativeCheckpointContext.SCHEMA);
      assertThat(f.checkpoint.getChainArtifactChecksum()).isNull();
      var resumed = new ReplayWorker.ExecutionState("unused");
      var coordinates =
          f.worker.restoreNativeCheckpoint(f.heartbeat, f.kernel, child, m, f.runtime(), resumed);
      assertThat(coordinates.actionIndex()).isEqualTo(1);
      assertThat(coordinates.tick()).isZero();
      assertThat(resumed.nativeLedger.evidence()).isEqualTo(es.nativeLedger.evidence());
      assertThat(resumed.chainHash).isEqualTo(es.chainHash);
      assertThat(resumed.executedActions).isEqualTo(1);
      assertThat(resumed.lastPersistedOrdinal).isEqualTo(6);
      assertThat(resumed.lastSampleTick).isZero();
      assertThat(resumed.lastSampleSequence).isZero();
      verify(f.kernel, never()).send(eq("reset"), any(), anyLong());
      verify(f.kernel, never()).send(eq("step"), any(), anyLong());
    }
  }

  @Test
  void restoredTerminalCheckpointNeverDispatchesAnotherStepOrResurrectsItsSuffix() {
    ObjectNode m = manifest();
    var es = prefix(m, true);
    ObjectNode snap = snapshot(m, es);
    try (var f = new Fixture(m)) {
      f.record(es, snap);
      ReplayRun child = f.child();
      f.restoreReplies(snap, es);
      var resumed = new ReplayWorker.ExecutionState("unused");
      var coordinates =
          f.worker.restoreNativeCheckpoint(f.heartbeat, f.kernel, child, m, f.runtime(), resumed);
      assertThat(
              f.worker.executeSourceSchedule(
                  f.kernel,
                  f.heartbeat,
                  new ReplayFence(child.getId(), UUID.randomUUID(), 1, 1, "child"),
                  child,
                  resumed,
                  m,
                  f.runtime(),
                  coordinates.actionIndex(),
                  coordinates.tick()))
          .isZero();
      assertThat(resumed.nativeLedger.terminal()).isTrue();
      assertThat(resumed.executedActions).isEqualTo(1);
      assertThat(resumed.nativeLedger.evidence().at("/sourcePrefix/unconsumedCount").asInt())
          .isEqualTo(1);
      verify(f.kernel, never()).send(eq("step"), any(), anyLong());
      verify(f.kernel, never()).send(eq("reset"), any(), anyLong());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-context",
        "schema",
        "extra",
        "context-tick",
        "action-index",
        "prefix-digest",
        "run-state-digest",
        "runtime-digest",
        "manifest-digest",
        "snapshot-digest",
        "internal-checksum",
        "state-hash",
        "chain-hash",
        "checkpoint-id",
        "attempt-id",
        "branch-key",
        "parent-checkpoint",
        "owner",
        "parent-running",
        "manifest-id",
        "parent-manifest",
        "parent-policy",
        "missing-ref",
        "unfinalized",
        "bytes",
        "chain-artifact",
        "oversize-context",
        "oversize-snapshot",
        "duplicate-key",
        "trailing-json"
      })
  void alteredOrUnboundAnchorsDispatchNoNativeStateRequest(String mutation) throws Exception {
    ObjectNode m = manifest();
    var es = prefix(m, false);
    ObjectNode snap = snapshot(m, es);
    try (var f = new Fixture(m)) {
      f.record(es, snap);
      ReplayRun child = f.child();
      ObjectNode ctx = (ObjectNode) JSON.readTree(f.checkpoint.getRestoreContext());
      switch (mutation) {
        case "missing-context" -> f.checkpoint.setRestoreContext(null);
        case "schema" -> ctx.put("schema", "causal-v2");
        case "extra" -> ctx.put("extra", true);
        case "context-tick" -> ctx.put("tick", 1);
        case "action-index" -> f.checkpoint.setActionIndex(0);
        case "prefix-digest" -> ctx.put("prefixDigest", "sha256:" + "c".repeat(64));
        case "run-state-digest" -> ctx.put("runStateDigest", "sha256:" + "c".repeat(64));
        case "runtime-digest" -> ctx.put("runtimeDigest", "sha256:" + "c".repeat(64));
        case "manifest-digest" -> ctx.put("manifestDigest", "sha256:" + "c".repeat(64));
        case "snapshot-digest" -> ctx.put("snapshotDigest", "sha256:" + "c".repeat(64));
        case "internal-checksum" -> f.checkpoint.setSnapshotChecksum("sha256:" + "c".repeat(64));
        case "state-hash" -> f.checkpoint.setStateHash("sha256:" + "c".repeat(64));
        case "chain-hash" -> f.checkpoint.setChainHash("sha256:" + "c".repeat(64));
        case "checkpoint-id" -> ctx.put("checkpointId", UUID.randomUUID().toString());
        case "attempt-id" -> ctx.put("attemptId", UUID.randomUUID().toString());
        case "branch-key" -> ctx.put("branchKey", "foreign");
        case "parent-checkpoint" -> ctx.put("parentCheckpointId", UUID.randomUUID().toString());
        case "owner" -> child.setOwnerId("foreign");
        case "parent-running" -> f.parent.setState(ReplayRunState.RUNNING);
        case "manifest-id" -> child.setManifestId(UUID.randomUUID());
        case "parent-manifest" -> f.parent.setManifestChecksum("sha256:" + "c".repeat(64));
        case "parent-policy" -> child.setStopPolicy(ReplayStopPolicy.COLLECT_ALL);
        case "missing-ref" -> when(f.refs.findByRunIdAndKindAndChecksum(any(), any(), any()))
            .thenReturn(Optional.empty());
        case "unfinalized" -> f.ref.setState(ArtifactRef.STATE_STAGING);
        case "bytes" -> f.blobs.get(f.ref.getChecksum())[0] = '[';
        case "chain-artifact" -> f.checkpoint.setChainArtifactChecksum("sha256:" + "c".repeat(64));
        case "oversize-context" -> ctx.put("schema", "x".repeat(8193));
        case "oversize-snapshot" -> f.properties.getXrsps().setMaxStdoutLineBytes(1);
        case "duplicate-key" -> f.checkpoint.setRestoreContext(
            f.checkpoint.getRestoreContext().replace("{", "{\"schema\":\"duplicate\","));
        case "trailing-json" -> f.checkpoint.setRestoreContext(
            f.checkpoint.getRestoreContext() + " {}");
        default -> throw new AssertionError(mutation);
      }
      if (!Set.of("missing-context", "duplicate-key", "trailing-json").contains(mutation))
        f.checkpoint.setRestoreContext(JSON.writeValueAsString(ctx));
      clearInvocations(f.kernel);
      var resumed = new ReplayWorker.ExecutionState("unchanged");
      assertThatThrownBy(
              () ->
                  f.worker.restoreNativeCheckpoint(
                      f.heartbeat, f.kernel, child, m, f.runtime(), resumed))
          .isInstanceOf(ReplayException.class);
      verifyNoInteractions(f.kernel);
      assertThat(resumed.nativeLedger).isNull();
      assertThat(resumed.chainHash).isEqualTo("unchanged");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"restore-tick", "restore-state", "restore-observation", "observe", "readback"})
  void badNativeReplayReadbackNeverCommitsTheDriverOrDispatchesASuffix(String mutation) {
    ObjectNode m = manifest();
    var es = prefix(m, false);
    ObjectNode snap = snapshot(m, es);
    try (var f = new Fixture(m)) {
      f.record(es, snap);
      ReplayRun child = f.child();
      f.restoreReplies(snap, es);
      ObjectNode r = JSON.createObjectNode().put("restoredTick", 0);
      r.set("runState", es.nativeLedger.state());
      r.putObject("observation").put("tick", 0);
      switch (mutation) {
        case "restore-tick" -> r.put("restoredTick", 1);
        case "restore-state" -> ((ObjectNode) r.path("runState")).put("acceptedActions", 0);
        case "restore-observation" -> ((ObjectNode) r.path("observation")).put("extra", true);
        case "observe" -> when(f.kernel.send(eq("observe"), isNull(), anyLong()))
            .thenReturn(message(JSON.createObjectNode().putObject("observation").put("tick", 1)));
        case "readback" -> {
          ObjectNode altered = snap.deepCopy().put("extra", true);
          when(f.kernel.send(eq("snapshot"), isNull(), anyLong()))
              .thenReturn(message(JSON.createObjectNode().set("snapshot", altered)));
        }
      }
      when(f.kernel.send(eq("restore"), any(), anyLong())).thenReturn(message(r));
      var resumed = new ReplayWorker.ExecutionState("unchanged");
      assertThatThrownBy(
              () ->
                  f.worker.restoreNativeCheckpoint(
                      f.heartbeat, f.kernel, child, m, f.runtime(), resumed))
          .isInstanceOf(ReplayException.class);
      assertThat(resumed.nativeLedger).isNull();
      assertThat(resumed.executedActions).isZero();
      verify(f.kernel, never()).send(eq("step"), any(), anyLong());
    }
  }
}
