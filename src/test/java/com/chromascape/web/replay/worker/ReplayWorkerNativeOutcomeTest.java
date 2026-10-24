package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayRunState;
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
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Real production source iteration/evidence builder; controlled transport and persistence, not
 * DB/native parity.
 */
class ReplayWorkerNativeOutcomeTest {
  private static final RuntimeIdentity RUNTIME =
      new RuntimeIdentity("0.1.0", "build", "cache", "content");
  private final ObjectMapper json = new ObjectMapper();
  private final ReplayRunRepository runs = mock(ReplayRunRepository.class);
  private final ReplayWorkerPersistenceService persistence =
      mock(ReplayWorkerPersistenceService.class);
  private final ReplayArtifactService artifacts = mock(ReplayArtifactService.class);
  final XrspsKernelProcess kernel = mock(XrspsKernelProcess.class);
  private final ReplayFence fence =
      new ReplayFence(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "native-test");
  private final ReplayRun run = new ReplayRun();
  private final ReplayLeaseHeartbeat.Handle heartbeat = mock(ReplayLeaseHeartbeat.Handle.class);
  private final CanonicalProjectionService projection = new CanonicalProjectionService();

  ReplayWorker worker() {
    run.setId(fence.runId());
    run.setState(ReplayRunState.RUNNING);
    when(runs.findById(run.getId())).thenReturn(Optional.of(run));
    when(runs.remainingDeadlineMs(eq(run.getId()), anyLong())).thenReturn(60_000L);
    ReplayWorker worker =
        spy(
            new ReplayWorker(
                runs,
                mock(ReplayAttemptRepository.class),
                mock(ReplayRunService.class),
                mock(ScenarioManifestRepository.class),
                mock(ReplayCheckpointRepository.class),
                mock(ArtifactRefRepository.class),
                mock(ReplayMismatchRepository.class),
                persistence,
                mock(ReplayLeaseHeartbeat.class),
                mock(ReplayExpiredAttemptRecoveryService.class),
                artifacts,
                mock(MismatchEngine.class),
                mock(ReplayReportService.class),
                projection,
                new ReplayProperties(),
                json));
    doNothing()
        .when(worker)
        .takeCheckpoint(any(), any(), any(), any(), anyLong(), anyInt(), any(), any(), any());
    return worker;
  }

  private ObjectNode manifest() {
    ObjectNode m =
        json.createObjectNode()
            .put("manifestVersion", 2)
            .put("manifestChecksum", "sha256:" + "a".repeat(64));
    m.putObject("recording").put("startTick", 0).put("endTick", 20);
    m.putObject("execution")
        .put("schema", "xrsps-execution-limits/v1")
        .put("maximumTicks", 20)
        .put("maximumActions", 100)
        .put("maximumSteps", 100);
    var actions = m.putArray("actions");
    for (int tick : List.of(5, 10))
      actions
          .addObject()
          .put("type", "INTERACT_NPC")
          .put("targetStableId", "spot")
          .put("option", "net")
          .put("sourceActionTick", tick)
          .put("sourceActionSequence", 0);
    ObjectNode policy = m.putObject("termination").put("schema", "xrsps-termination-policy/v1");
    for (String category : List.of("success", "failure", "stop"))
      policy
          .putArray(category)
          .addObject()
          .put("id", category)
          .putObject("predicate")
          .put("op", "exists")
          .put("pointer", "/known");
    m.putObject("curation")
        .putObject("sourceCuration")
        .putObject("contentSnapshot")
        .set("termination", policy.deepCopy());
    return m;
  }

  private ObjectNode state(ObjectNode manifest, int tick, int actions, int steps, String reason) {
    ObjectNode state =
        json.createObjectNode()
            .put("schema", NativeRunLedger.SCHEMA)
            .put("startTick", 0)
            .put("tick", tick)
            .put("elapsedTicks", tick)
            .put("acceptedActions", actions)
            .put("acceptedSteps", steps);
    state.set("limits", manifest.path("execution").deepCopy());
    if (reason == null)
      return state
          .put("status", "RUNNING")
          .putNull("reason")
          .putNull("outcome")
          .putNull("conditionId")
          .putNull("terminalTick");
    state.put("status", "TERMINAL").put("terminalTick", tick);
    if (reason.equals("success") || reason.equals("failure") || reason.equals("stop"))
      return state
          .put("reason", "CONDITION")
          .put(
              "outcome",
              reason.equals("stop") ? "STOPPED" : reason.toUpperCase(java.util.Locale.ROOT))
          .put("conditionId", reason);
    return state.put("reason", reason).put("outcome", "INCONCLUSIVE").putNull("conditionId");
  }

  private ReplayWorker.ExecutionState execution(ObjectNode manifest, String initialReason) {
    var es = new ReplayWorker.ExecutionState("sha256:" + "b".repeat(64));
    es.nativeLedger = new NativeRunLedger(manifest);
    es.nativeLedger.acceptReset(
        json.createObjectNode()
            .put("initialTick", 0)
            .set("runState", state(manifest, 0, 0, 0, initialReason)));
    es.lastStateHash = projection.stateHash(json.createObjectNode().put("tick", 0));
    return es;
  }

  private KernelMessage response(ObjectNode state, boolean action) {
    ObjectNode result =
        json.createObjectNode()
            .put("terminalTick", state.path("tick").asInt())
            .put("actionApplied", action);
    result.set("runState", state);
    result.putObject("observation").put("tick", state.path("tick").asInt());
    result.putArray("events");
    return new KernelMessage("step", null, result, null, false, false);
  }

  long execute(ReplayWorker worker, ObjectNode manifest, ReplayWorker.ExecutionState es) {
    return worker.executeSourceSchedule(kernel, heartbeat, fence, run, es, manifest, null, 0, 0);
  }

  @Test
  void initialOutcomeSendsNoGameplayAndCheckpointsZeroConsumedActions() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, "success");
    try (var worker = worker()) {
      assertThat(execute(worker, manifest, es)).isZero();
      verifyNoInteractions(kernel);
      verify(persistence).initializeNativeSourceCursor(eq(fence), any());
      verify(worker).takeCheckpoint(kernel, heartbeat, fence, run, 0, -1, es, manifest, null);
      assertThat(es.executedActions).isZero();
      assertThat(es.nativeLedger.evidence().at("/sourcePrefix/unconsumedCount").asInt())
          .isEqualTo(2);
    }
  }

  @Test
  void actionlessConditionBeforeFirstReceiptStopsRealLoopAndDoesNotInventAnExecutedAction() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, null);
    when(kernel.send(eq("step"), any(), anyLong()))
        .thenReturn(response(state(manifest, 3, 0, 1, "stop"), false));
    try (var worker = worker()) {
      assertThat(execute(worker, manifest, es)).isEqualTo(3);
      assertThat(es.executedActions).isZero();
      var requests = ArgumentCaptor.forClass(JsonNode.class);
      verify(kernel).send(eq("step"), requests.capture(), anyLong());
      assertThat(requests.getValue().has("action")).isFalse();
      verify(worker).takeCheckpoint(kernel, heartbeat, fence, run, 3, -1, es, manifest, null);
      verify(persistence).appendNativeActionResult(eq(fence), anyList(), any(), any());
    }
  }

  @Test
  void zeroTickActionSuccessConsumesItsReceiptButNotTheRemainingSourceSuffix() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, null);
    when(kernel.send(eq("step"), any(), anyLong()))
        .thenReturn(
            response(state(manifest, 5, 0, 1, null), false),
            response(state(manifest, 5, 1, 2, "success"), true));
    try (var worker = worker()) {
      assertThat(execute(worker, manifest, es)).isEqualTo(5);
      assertThat(es.executedActions).isEqualTo(1);
      verify(kernel, times(2)).send(eq("step"), any(), anyLong());
      verify(worker).takeCheckpoint(kernel, heartbeat, fence, run, 5, 0, es, manifest, null);
      assertThat(es.nativeLedger.evidence().at("/sourcePrefix/unconsumedCount").asInt())
          .isEqualTo(1);
    }
  }

  @Test
  void tickBudgetBeforeFirstSourceIsInconclusiveNotASequenceError() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("execution")).put("maximumTicks", 3);
    var es = execution(manifest, null);
    when(kernel.send(eq("step"), any(), anyLong()))
        .thenReturn(response(state(manifest, 3, 0, 1, "TICK_LIMIT"), false));
    try (var worker = worker()) {
      assertThat(execute(worker, manifest, es)).isEqualTo(3);
      assertThat(es.nativeLedger.state().path("outcome").asText()).isEqualTo("INCONCLUSIVE");
      assertThat(es.executedActions).isZero();
      verify(kernel).send(eq("step"), any(), anyLong());
    }
  }

  @Test
  void sourceTailConditionStopsAfterAllReceiptsButDoesNotClaimRecordingEnd() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, null);
    when(kernel.send(eq("step"), any(), anyLong()))
        .thenReturn(
            response(state(manifest, 5, 0, 1, null), false),
            response(state(manifest, 5, 1, 2, null), true),
            response(state(manifest, 10, 1, 3, null), false),
            response(state(manifest, 10, 2, 4, null), true),
            response(state(manifest, 12, 2, 5, "stop"), false));
    try (var worker = worker()) {
      assertThat(execute(worker, manifest, es)).isEqualTo(12);
      assertThat(es.executedActions).isEqualTo(2);
      verify(kernel, times(5)).send(eq("step"), any(), anyLong());
      verify(worker).takeCheckpoint(kernel, heartbeat, fence, run, 12, 1, es, manifest, null);
      assertThat(es.nativeLedger.evidence().at("/sourcePrefix/recordingEndReached").asBoolean())
          .isFalse();
    }
  }

  @Test
  void forgedOutcomeOrObservationPersistsNothingAndDoesNotAdvanceTrustedPrefix() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, null);
    ObjectNode forged = state(manifest, 3, 0, 1, "stop").put("conditionId", "forged");
    when(kernel.send(eq("step"), any(), anyLong())).thenReturn(response(forged, false));
    try (var worker = worker()) {
      assertThatThrownBy(() -> execute(worker, manifest, es)).isInstanceOf(ReplayException.class);
      verify(persistence).initializeNativeSourceCursor(eq(fence), any());
      verify(persistence, never()).appendNativeActionResult(any(), any(), any(), any());
      assertThat(es.nativeLedger.state().path("acceptedSteps").asInt()).isZero();
      KernelMessage badObservation = response(state(manifest, 3, 0, 1, "stop"), false);
      ((ObjectNode) badObservation.result().path("observation")).put("tick", 4);
      when(kernel.send(eq("step"), any(), anyLong())).thenReturn(badObservation);
      assertThatThrownBy(() -> execute(worker, manifest, es))
          .hasMessageContaining("observation tick");
      verify(persistence).initializeNativeSourceCursor(eq(fence), any());
      verify(persistence, never()).appendNativeActionResult(any(), any(), any(), any());
      assertThat(es.nativeLedger.state().path("acceptedSteps").asInt()).isZero();
    }
  }

  @Test
  void sourceSuffixContinuesTheCommittedAttemptWithoutRebindingItsAcceptedPrefix() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, null);
    when(kernel.send(eq("step"), any(), anyLong()))
        .thenReturn(
            response(state(manifest, 5, 0, 1, null), false),
            response(state(manifest, 5, 1, 2, null), true),
            response(state(manifest, 10, 1, 3, null), false),
            response(state(manifest, 10, 2, 4, null), true),
            response(state(manifest, 12, 2, 5, "stop"), false));
    try (var worker = worker()) {
      worker.initializeNativeSourceCursor(fence, run, es);
      var schedule = new ReplaySourceSchedule(manifest);
      long tick =
          worker.executeScheduledStep(
              kernel, heartbeat, fence, run, es, schedule.next(0, 0, 100), 0);
      tick =
          worker.executeScheduledStep(
              kernel, heartbeat, fence, run, es, schedule.next(0, tick, 100), 0);
      es.executedActions++;
      assertThat(
              worker.executeSourceSchedule(
                  kernel, heartbeat, fence, run, es, manifest, null, 1, tick))
          .isEqualTo(12);
      assertThat(es.executedActions).isEqualTo(2);
      verify(persistence).initializeNativeSourceCursor(eq(fence), any());
      verify(persistence, times(5)).appendNativeActionResult(eq(fence), anyList(), any(), any());
      var requests = ArgumentCaptor.forClass(JsonNode.class);
      verify(kernel, times(5)).send(eq("step"), requests.capture(), anyLong());
      assertThat(
              requests.getAllValues().stream()
                  .filter(request -> request.has("action"))
                  .map(request -> request.path("sourceActionIndex").asInt())
                  .toList())
          .containsExactly(0, 1);
      var foreign = new ReplayFence(fence.runId(), UUID.randomUUID(), 1, 1, "foreign");
      assertThatThrownBy(() -> worker.initializeNativeSourceCursor(foreign, run, es))
          .hasMessageContaining("different attempt");
      verify(persistence).initializeNativeSourceCursor(any(), any());
    }
  }

  @Test
  void nativeOutcomeIsRegisteredUnderTheFenceWithExplicitPrefixScopeAndChecksumReference()
      throws Exception {
    ObjectNode manifest = manifest();
    var es = execution(manifest, "failure");
    var prepared =
        new PreparedArtifact(
            UUID.randomUUID(),
            fence.runId(),
            ArtifactKind.OUTCOME,
            "sha256:" + "c".repeat(64),
            1,
            "application/json",
            Path.of("unused-test-staging"));
    when(artifacts.prepareArtifact(
            eq(fence.runId()), eq(ArtifactKind.OUTCOME), any(), eq("application/json")))
        .thenReturn(prepared);
    var ref =
        new ArtifactRef(
            UUID.randomUUID(), fence.runId(), ArtifactKind.OUTCOME, prepared.checksum(), 1);
    when(persistence.registerArtifact(fence, prepared))
        .thenReturn(new ArtifactRegistration(ref, true));
    try (var worker = worker()) {
      worker.captureNativeOutcome(fence, es);
      var bytes = ArgumentCaptor.forClass(byte[].class);
      verify(artifacts)
          .prepareArtifact(
              eq(fence.runId()), eq(ArtifactKind.OUTCOME), bytes.capture(), eq("application/json"));
      JsonNode evidence = json.readTree(bytes.getValue());
      assertThat(evidence.path("conformanceScope").asText()).isEqualTo("SOURCE_PREFIX");
      assertThat(evidence.at("/runState/outcome").asText()).isEqualTo("FAILURE");
      assertThat(evidence.at("/sourcePrefix/unconsumedCount").asInt()).isEqualTo(2);
      assertThat(es.artifactChecksums).containsExactly(prepared.checksum());
      verify(persistence).registerArtifact(fence, prepared);
      verify(artifacts, never()).publishArtifacts(any());
    }
  }

  private ObjectNode initialSnapshot(ObjectNode manifest, ReplayWorker.ExecutionState es) {
    ObjectNode snapshot =
        json.createObjectNode()
            .put("snapshotVersion", 1)
            .put("tick", 0)
            .put("canonicalStateHash", es.lastStateHash);
    snapshot.set("runtimeIdentity", json.valueToTree(RUNTIME));
    ObjectNode descriptor =
        snapshot
            .putArray("contentState")
            .addObject()
            .put("kind", "authoritative-reconstruction-v1")
            .put("descriptorVersion", 4)
            .put("runEventSchema", 2)
            .put("resetMode", "offline")
            .put("tick", 0)
            .put("manifestChecksum", manifest.path("manifestChecksum").asText())
            .put("observationHash", es.lastStateHash);
    descriptor.set("manifest", manifest.deepCopy());
    descriptor.set("runState", es.nativeLedger.state());
    descriptor.putArray("steps");
    return seal(snapshot);
  }

  private ObjectNode seal(ObjectNode snapshot) {
    snapshot.remove("checksum");
    snapshot.put("checksum", projection.sha256Hex(projection.canonicalJsonBytes(snapshot)));
    return snapshot;
  }

  private void checkpointResponse(ObjectNode snapshot) {
    when(kernel.send(eq("snapshot"), isNull(), anyLong()))
        .thenReturn(
            new KernelMessage(
                "snapshot",
                null,
                json.createObjectNode().set("snapshot", snapshot),
                null,
                false,
                false));
  }

  private void realCheckpoint(ReplayWorker worker) {
    doCallRealMethod()
        .when(worker)
        .takeCheckpoint(any(), any(), any(), any(), anyLong(), anyInt(), any(), any(), any());
  }

  @Test
  void realNativeCheckpointRegistrationBindsInitialTerminalPrefixUnderTheFence() throws Exception {
    ObjectNode manifest = manifest();
    manifest.set("runtime", json.valueToTree(RUNTIME));
    var es = execution(manifest, "stop");
    ObjectNode snapshot = initialSnapshot(manifest, es);
    checkpointResponse(snapshot);
    byte[] expectedBytes = projection.canonicalJsonBytes(snapshot);
    var prepared =
        new PreparedArtifact(
            UUID.randomUUID(),
            fence.runId(),
            ArtifactKind.SNAPSHOT,
            projection.sha256Hex(expectedBytes),
            expectedBytes.length,
            "application/json",
            Path.of("unused-snapshot-staging"));
    var ref =
        new ArtifactRef(
            UUID.randomUUID(),
            fence.runId(),
            ArtifactKind.SNAPSHOT,
            prepared.checksum(),
            expectedBytes.length);
    when(artifacts.prepareArtifact(
            eq(fence.runId()), eq(ArtifactKind.SNAPSHOT), any(), eq("application/json")))
        .thenReturn(prepared);
    when(persistence.registerCheckpoint(eq(fence), eq(prepared), isNull(), any()))
        .thenReturn(new ArtifactRegistration(ref, true));
    try (var worker = worker()) {
      realCheckpoint(worker);
      worker.takeCheckpoint(kernel, heartbeat, fence, run, 0, -1, es, manifest, RUNTIME);
      var stored =
          ArgumentCaptor.forClass(com.chromascape.web.replay.run.entity.ReplayCheckpoint.class);
      verify(persistence).registerCheckpoint(eq(fence), eq(prepared), isNull(), stored.capture());
      assertThat(stored.getValue().getActionIndex()).isZero();
      assertThat(stored.getValue().getTick()).isZero();
      assertThat(stored.getValue().getStateHash()).isEqualTo(es.lastStateHash);
      assertThat(stored.getValue().getSnapshotChecksum())
          .isEqualTo(snapshot.path("checksum").asText());
      var bytes = ArgumentCaptor.forClass(byte[].class);
      verify(artifacts)
          .prepareArtifact(
              eq(fence.runId()),
              eq(ArtifactKind.SNAPSHOT),
              bytes.capture(),
              eq("application/json"));
      assertThat(bytes.getValue()).containsExactly(expectedBytes);
      assertThat(es.artifactChecksums).containsExactly(prepared.checksum());
      assertThat(es.checkpointCount).isEqualTo(1);
      verify(artifacts, never()).publishArtifacts(any());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "checksum",
        "observation",
        "canonicalState",
        "missingRuntime",
        "foreignRuntime",
        "manifest",
        "prefix",
        "ledger"
      })
  void poisonedNativeCheckpointCannotPrepareOrRegisterAnyArtifact(String mutation) {
    ObjectNode manifest = manifest();
    manifest.set("runtime", json.valueToTree(RUNTIME));
    var es = execution(manifest, "stop");
    JsonNode originalLedger = es.nativeLedger.state();
    ObjectNode snapshot = initialSnapshot(manifest, es);
    ObjectNode descriptor = (ObjectNode) snapshot.path("contentState").get(0);
    switch (mutation) {
      case "checksum" -> snapshot.put("checksum", "sha256:" + "f".repeat(64));
      case "observation" -> descriptor.put("observationHash", "sha256:" + "f".repeat(64));
      case "canonicalState" -> snapshot.put("canonicalStateHash", "sha256:" + "f".repeat(64));
      case "missingRuntime" -> snapshot.remove("runtimeIdentity");
      case "foreignRuntime" -> ((ObjectNode) snapshot.path("runtimeIdentity"))
          .put("contentRevision", "foreign");
      case "manifest" -> descriptor.put("manifestChecksum", "sha256:" + "f".repeat(64));
      case "prefix" -> descriptor.withArray("steps").addObject().put("expectedTick", 0);
      case "ledger" -> ((ObjectNode) descriptor.path("runState")).put("acceptedSteps", 1);
      default -> throw new AssertionError(mutation);
    }
    if (!mutation.equals("checksum")) seal(snapshot);
    checkpointResponse(snapshot);
    try (var worker = worker()) {
      realCheckpoint(worker);
      assertThatThrownBy(
              () ->
                  worker.takeCheckpoint(
                      kernel, heartbeat, fence, run, 0, -1, es, manifest, RUNTIME))
          .isInstanceOf(ReplayException.class);
      verifyNoInteractions(artifacts, persistence);
      assertThat(es.checkpointCount).isZero();
      assertThat(es.artifactChecksums).isEmpty();
      assertThat(es.nativeLedger.state()).isEqualTo(originalLedger);
    }
  }

  @Test
  void refusedOutcomeRegistrationDiscardsPreparedBytesAndAddsNoReference() {
    ObjectNode manifest = manifest();
    var es = execution(manifest, "stop");
    var prepared =
        new PreparedArtifact(
            UUID.randomUUID(),
            fence.runId(),
            ArtifactKind.OUTCOME,
            "sha256:" + "c".repeat(64),
            1,
            "application/json",
            Path.of("unused-refused-outcome"));
    when(artifacts.prepareArtifact(
            eq(fence.runId()), eq(ArtifactKind.OUTCOME), any(), eq("application/json")))
        .thenReturn(prepared);
    when(persistence.registerArtifact(fence, prepared))
        .thenThrow(new IllegalStateException("fence refused"));
    try (var worker = worker()) {
      assertThatThrownBy(() -> worker.captureNativeOutcome(fence, es)).hasMessage("fence refused");
      verify(artifacts).discardPrepared(prepared);
      assertThat(es.artifactChecksums).isEmpty();
      verify(artifacts, never()).publishArtifacts(any());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nonterminalOrWrongConsumedCountCannotPrepareOutcomeEvidence(boolean running) {
    ObjectNode manifest = manifest();
    var es = execution(manifest, running ? null : "stop");
    if (!running) es.executedActions = 1;
    try (var worker = worker()) {
      assertThatThrownBy(() -> worker.captureNativeOutcome(fence, es))
          .isInstanceOf(ReplayException.class);
      verifyNoInteractions(artifacts, persistence);
      assertThat(es.artifactChecksums).isEmpty();
    }
  }
}
