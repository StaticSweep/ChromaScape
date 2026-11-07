package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.api.dto.*;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.kernel.BoundedUtf8LineReader;
import com.chromascape.web.replay.kernel.KernelProtocolCodec;
import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.manifest.contract.RuntimeIdentity;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.dto.BranchReplayRunRequest;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Explicit-lane body joining real repositories/transactions/artifacts to fresh native processes.
 * Selected only by the explicit continuation lane. Authored input is never public compiler or
 * certification authority. The Windows test transport below is NOT a production sandbox fallback or
 * OS-isolation proof.
 */
final class NativeDatabaseContinuation {
  // Measured cold worker startup on this host is 63-65 s (guards, WASM and kernel construction).
  // This remains a bounded authored-lane transport deadline; runtime resource budgets are
  // unchanged and production limits are not affected.
  private static final long NATIVE_REQUEST_DEADLINE_MS = 180_000;

  private final NativeCheckpointPersistenceTest t;

  private NativeDatabaseContinuation(NativeCheckpointPersistenceTest test) {
    t = test;
  }

  static void verify(NativeCheckpointPersistenceTest test, Path input, Path output, Path runtime)
      throws Exception {
    new NativeDatabaseContinuation(test).verify(input, output, runtime, false);
  }

  static void prepare(NativeCheckpointPersistenceTest test, Path input, Path output, Path runtime)
      throws Exception {
    new NativeDatabaseContinuation(test).verify(input, output, runtime, true);
  }

  static void resume(
      NativeCheckpointPersistenceTest test, Path input, Path prepared, Path output, Path runtime)
      throws Exception {
    new NativeDatabaseContinuation(test).resume(input, prepared, output, runtime);
  }

  private void verify(Path input, Path output, Path runtime, boolean prepareOnly) throws Exception {
    byte[] original = Files.readAllBytes(input);
    JsonNode bundle = t.json.readTree(original);
    assertThat(bundle.path("origin").asText()).isEqualTo("AUTHORED_TEST_ONLY");
    assertThat(bundle.path("variants")).hasSize(3);
    assertThat(Files.exists(output)).isFalse();
    Files.createDirectories(output);
    ObjectNode report =
        t.json
            .createObjectNode()
            .put("origin", "AUTHORED_TEST_ONLY")
            .put("inputChecksum", t.hashes.sha256Hex(original))
            .put("publicAdmissionQualified", false)
            .put("productionSandboxQualified", false)
            .put("freshJvmQualified", false)
            .put("recoveryAndHttpExportQualified", false)
            .put("jvmPid", ProcessHandle.current().pid())
            .put("phase", prepareOnly ? "PREPARED_FOR_FRESH_JVM" : "SAME_JVM_CONTINUATION")
            .put("certifiable", false)
            .put("allVariantsPassed", false);
    var results = report.putArray("variants");
    int ordinal = 0;
    try {
      for (JsonNode variant : bundle.path("variants")) {
        ObjectNode manifest = (ObjectNode) variant.path("manifest");
        ObjectNode unhashed = manifest.deepCopy();
        unhashed.remove("manifestChecksum");
        assertThat(manifest.path("manifestChecksum").asText())
            .isEqualTo(t.hashes.stateHash(unhashed));
        ObjectNode result = results.addObject().put("name", variant.path("name").asText());
        verifyVariant(variant, manifest, runtime, output, ++ordinal, result, prepareOnly);
        Files.writeString(
            output.resolve("variant-" + ordinal + ".json"),
            t.json.writerWithDefaultPrettyPrinter().writeValueAsString(result),
            StandardOpenOption.CREATE_NEW);
      }
      assertThat(results).hasSize(3);
      assertThat(
              java.util.stream.StreamSupport.stream(results.spliterator(), false)
                  .filter(result -> result.path("prefixTerminal").asBoolean())
                  .count())
          .isEqualTo(2);
      if (prepareOnly) {
        prepareActionlessAdvance(
            (ObjectNode) bundle.path("variants").get(2).path("manifest"), runtime, output, report);
      }
      report.put("allVariantsPassed", true);
    } finally {
      Files.writeString(
          output.resolve("native-database-continuation.json"),
          t.json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
          StandardOpenOption.CREATE_NEW);
      assertThat(Files.readAllBytes(input)).containsExactly(original);
    }
  }

  private void prepareActionlessAdvance(
      ObjectNode sourceManifest, Path runtime, Path output, ObjectNode report) throws Exception {
    // The original first source receipt is due at tick zero. Give this separate authored
    // fixture an idle tick without bypassing the runtime's source-action ordering guard.
    ObjectNode manifest = sourceManifest.deepCopy();
    ((ObjectNode) manifest.path("actions").get(0)).put("sourceActionTick", 2);
    manifest.remove("manifestChecksum");
    manifest.put("manifestChecksum", t.hashes.stateHash(manifest));
    try (var abandoned = new StoredRun(manifest, null);
        var nativeWorker = new NativeTransport(runtime, output, 7)) {
      assertCapability(
          nativeWorker.send("capabilities", null, NATIVE_REQUEST_DEADLINE_MS).result().path("capabilities"), manifest);
      JsonNode reset =
          nativeWorker
              .send("reset", t.json.createObjectNode().set("manifest", manifest), NATIVE_REQUEST_DEADLINE_MS)
              .result();
      var state = new ReplayWorker.ExecutionState("sha256:" + "b".repeat(64));
      state.nativeLedger = new NativeRunLedger(manifest);
      state.nativeLedger.acceptReset(reset);
      state.lastStateHash = t.hashes.stateHash(reset.path("initialObservation"));
      abandoned.worker.recordResetEvents(abandoned.fence, reset, state);
      abandoned.worker.initializeNativeSourceCursor(abandoned.fence, abandoned.run, state);
      assertThat(
              abandoned.worker.executeScheduledStep(
                  nativeWorker,
                  abandoned.heartbeat,
                  abandoned.fence,
                  abandoned.run,
                  state,
                  t.json.createObjectNode().put("expectedTick", 0).put("maxTicks", 1),
                  0))
          .isEqualTo(1);
      assertThat(state.executedActions).isZero();
      assertThat(samples(abandoned.run.getId())).hasSize(1);
      JsonNode cursor =
          t.attempts.findById(abandoned.fence.attemptId()).orElseThrow().getNativeSourceCursor();
      assertThat(NativeSourceCursor.acceptedActions(cursor)).isZero();
      assertThat(cursor.path("acceptedSteps").asInt()).isEqualTo(1);
      report
          .putObject("abandonedAdvance")
          .put("runId", abandoned.run.getId().toString())
          .put("attemptId", abandoned.fence.attemptId().toString())
          .put("generation", abandoned.fence.fencingGeneration())
          .put("manifestChecksum", manifest.path("manifestChecksum").asText())
          .put("sourceActions", 0)
          .put("hashSamples", 1)
          .set("cursor", cursor);
    }
  }

  private void resume(Path input, Path prepared, Path output, Path runtime) throws Exception {
    byte[] original = Files.readAllBytes(input);
    byte[] preparedBytes = Files.readAllBytes(prepared);
    JsonNode bundle = t.json.readTree(original);
    JsonNode previous = t.json.readTree(preparedBytes);
    assertThat(bundle.path("origin").asText()).isEqualTo("AUTHORED_TEST_ONLY");
    assertThat(previous.path("phase").asText()).isEqualTo("PREPARED_FOR_FRESH_JVM");
    assertThat(previous.path("allVariantsPassed").asBoolean()).isTrue();
    assertThat(previous.path("inputChecksum").asText()).isEqualTo(t.hashes.sha256Hex(original));
    assertThat(previous.path("jvmPid").asLong()).isNotEqualTo(ProcessHandle.current().pid());
    assertThat(bundle.path("variants")).hasSize(3);
    assertThat(previous.path("variants")).hasSize(3);
    assertThat(Files.exists(output)).isFalse();
    Files.createDirectories(output);
    ObjectNode report = ((ObjectNode) previous).deepCopy();
    report.put("preparedChecksum", t.hashes.sha256Hex(preparedBytes));
    report.put("resumeJvmPid", ProcessHandle.current().pid());
    report.put("phase", "FRESH_JVM_CONTINUATION");
    report.put("allVariantsPassed", false);
    try {
      for (int index = 0; index < 3; index++) {
        JsonNode variant = bundle.path("variants").get(index);
        ObjectNode result = (ObjectNode) report.path("variants").get(index);
        assertThat(result.path("name").asText()).isEqualTo(variant.path("name").asText());
        resumeVariant((ObjectNode) variant.path("manifest"), runtime, output, index + 1, result);
        Files.writeString(
            output.resolve("variant-" + (index + 1) + ".json"),
            t.json.writerWithDefaultPrettyPrinter().writeValueAsString(result),
            StandardOpenOption.CREATE_NEW);
      }
      report.put("freshJvmQualified", true);
      report.put("allVariantsPassed", true);
    } finally {
      Files.writeString(
          output.resolve("native-database-continuation.json"),
          t.json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
          StandardOpenOption.CREATE_NEW);
      assertThat(Files.readAllBytes(input)).containsExactly(original);
      assertThat(Files.readAllBytes(prepared)).containsExactly(preparedBytes);
    }
  }

  private void verifyVariant(
      JsonNode variant,
      ObjectNode manifest,
      Path runtime,
      Path output,
      int ordinal,
      ObjectNode result,
      boolean prepareOnly)
      throws Exception {
    try (var parent = new StoredRun(manifest, null);
        var nativeParent = new NativeTransport(runtime, output, ordinal * 2 - 1)) {
      JsonNode capability =
          nativeParent.send("capabilities", null, NATIVE_REQUEST_DEADLINE_MS).result().path("capabilities");
      assertCapability(capability, manifest);
      JsonNode reset =
          nativeParent
              .send("reset", t.json.createObjectNode().set("manifest", manifest), NATIVE_REQUEST_DEADLINE_MS)
              .result();
      var uninterrupted = new ReplayWorker.ExecutionState("sha256:" + "b".repeat(64));
      uninterrupted.nativeLedger = new NativeRunLedger(manifest);
      uninterrupted.nativeLedger.acceptReset(reset);
      uninterrupted.lastStateHash = t.hashes.stateHash(reset.path("initialObservation"));
      parent.worker.recordResetEvents(parent.fence, reset, uninterrupted);
      parent.worker.initializeNativeSourceCursor(parent.fence, parent.run, uninterrupted);
      long tick = reset.path("initialTick").asLong();
      if (!uninterrupted.nativeLedger.terminal()) {
        ObjectNode first = new ReplaySourceSchedule(manifest).next(0, tick, 10_000);
        tick =
            parent.worker.executeScheduledStep(
                nativeParent, parent.heartbeat, parent.fence, parent.run, uninterrupted, first, 0);
        uninterrupted.executedActions++;
        if (!uninterrupted.nativeLedger.terminal()) {
          tick =
              parent.worker.executeScheduledStep(
                  nativeParent,
                  parent.heartbeat,
                  parent.fence,
                  parent.run,
                  uninterrupted,
                  t.json.createObjectNode().put("expectedTick", tick).put("maxTicks", 1),
                  1);
          assertThat(tick).isEqualTo(1);
        }
      }
      parent.worker.takeCheckpoint(
          nativeParent,
          parent.heartbeat,
          parent.fence,
          parent.run,
          tick,
          uninterrupted.executedActions - 1,
          uninterrupted,
          manifest,
          parent.identity);
      var anchor =
          t.checkpoints.findByRunIdAndBranchKeyOrderByTickAsc(parent.run.getId(), "main").get(0);
      assertThat(t.json.readTree(anchor.getRestoreContext()).path("schema").asText())
          .isEqualTo(NativeCheckpointContext.SCHEMA);
      assertThat(anchor.getChainArtifactChecksum()).isNull();
      assertThat(anchor.getActionIndex()).isEqualTo(uninterrupted.executedActions);
      long prefixNextOrdinal =
          nativeParent
              .send("snapshot", null, NATIVE_REQUEST_DEADLINE_MS)
              .result()
              .path("snapshot")
              .path("eventQueue")
              .path("nextOrdinal")
              .asLong();
      var prefixSamples = samples(parent.run.getId());
      long finalTick =
          uninterrupted.nativeLedger.terminal()
              ? tick
              : parent.worker.executeSourceSchedule(
                  nativeParent,
                  parent.heartbeat,
                  parent.fence,
                  parent.run,
                  uninterrupted,
                  manifest,
                  parent.identity,
                  uninterrupted.executedActions,
                  tick);
      JsonNode complete = nativeParent.send("snapshot", null, NATIVE_REQUEST_DEADLINE_MS).result().path("snapshot");
      JsonNode trace = nativeParent.send("trace", null, NATIVE_REQUEST_DEADLINE_MS).result();
      assertThat(uninterrupted.nativeLedger.terminal()).isTrue();
      assertThat(finalTick).isEqualTo(variant.path("tick").asLong());
      assertThat(uninterrupted.executedActions).isEqualTo(variant.path("consumedActions").asInt());
      assertThat(uninterrupted.nativeLedger.state().path("outcome").asText())
          .isEqualTo(variant.path("outcome").asText());
      parent.finalizeRun(uninterrupted, trace);
      assertThat(samples(parent.run.getId()).subList(0, prefixSamples.size()))
          .isEqualTo(prefixSamples);
      var snapshotRef =
          t.refs
              .findByRunIdAndKindAndChecksum(
                  parent.run.getId(), ArtifactKind.SNAPSHOT, anchor.getSnapshotArtifactChecksum())
              .orElseThrow();
      byte[] prefixBytes = t.artifacts.readFinalized(snapshotRef);
      assertThat(t.hashes.sha256Hex(prefixBytes)).isEqualTo(anchor.getSnapshotArtifactChecksum());
      result
          .put("parentRunId", parent.run.getId().toString())
          .put("checkpointId", anchor.getId().toString())
          .put("prefixTick", tick)
          .put("prefixActions", anchor.getActionIndex())
          .put(
              "prefixTerminal",
              t.json
                  .readTree(prefixBytes)
                  .path("contentState")
                  .get(0)
                  .path("runState")
                  .path("status")
                  .asText()
                  .equals("TERMINAL"));
      result.set("storedContext", t.json.readTree(anchor.getRestoreContext()));
      result.set("prefixSnapshot", t.json.readTree(prefixBytes));
      result.set("uninterruptedSnapshot", complete);
      result.set("uninterruptedTrace", trace);
      result.set("capability", capability);
      result.set("uninterruptedEvidence", uninterrupted.nativeLedger.evidence());
      result.set("parentRequests", nativeParent.requests.deepCopy());
      result
          .put("finalTick", finalTick)
          .put("finalActions", uninterrupted.executedActions)
          .put("finalStateHash", uninterrupted.lastStateHash)
          .put("finalChainHash", uninterrupted.chainHash)
          .put("finalEventOrdinal", uninterrupted.lastPersistedOrdinal)
          .put("prefixNextOrdinal", prefixNextOrdinal)
          .put("prefixSampleCount", prefixSamples.size());
      var branchRequest =
          new BranchReplayRunRequest(
              anchor.getId().toString(), "native-db-child", "native-db-" + UUID.randomUUID());
      UUID childId =
          t.runService.branch(parent.run.getId(), branchRequest, StoredRun.OWNER).runId();
      assertThat(t.runService.branch(parent.run.getId(), branchRequest, StoredRun.OWNER).runId())
          .isEqualTo(childId);
      result.put("childRunId", childId.toString());
      if (!prepareOnly) {
        resumeVariant(manifest, runtime, output, ordinal * 2, result);
      } else {
        UUID abandonedId =
            t.runService
                .branch(
                    parent.run.getId(),
                    new BranchReplayRunRequest(
                        anchor.getId().toString(),
                        "abandoned-restore",
                        "abandoned-restore-" + UUID.randomUUID()),
                    StoredRun.OWNER)
                .runId();
        try (var abandoned = new StoredRun(manifest, abandonedId);
            var transport = new NativeTransport(runtime, output, ordinal * 2)) {
          assertCapability(
              transport.send("capabilities", null, NATIVE_REQUEST_DEADLINE_MS).result().path("capabilities"), manifest);
          var state = new ReplayWorker.ExecutionState("before-abandoned-restore");
          abandoned.worker.restoreNativeCheckpoint(
              abandoned.heartbeat, transport, abandoned.run, manifest, abandoned.identity, state);
          abandoned.worker.initializeNativeSourceCursor(abandoned.fence, abandoned.run, state);
          JsonNode cursor =
              t.attempts
                  .findById(abandoned.fence.attemptId())
                  .orElseThrow()
                  .getNativeSourceCursor();
          assertThat(NativeSourceCursor.acceptedActions(cursor)).isEqualTo(anchor.getActionIndex());
          assertThat(samples(abandonedId)).isEmpty();
          result
              .putObject("abandonedRestore")
              .put("runId", abandonedId.toString())
              .put("attemptId", abandoned.fence.attemptId().toString())
              .put("generation", abandoned.fence.fencingGeneration())
              .put("sourceActions", anchor.getActionIndex())
              .set("cursor", cursor);
        }
      }
    }
  }

  private void resumeVariant(
      ObjectNode manifest, Path runtime, Path output, int ordinal, ObjectNode result)
      throws Exception {
    UUID childId = UUID.fromString(result.path("childRunId").asText());
    UUID parentId = UUID.fromString(result.path("parentRunId").asText());
    assertThat(t.runs.findById(childId).orElseThrow().getState()).isEqualTo(ReplayRunState.QUEUED);
    try (var child = new StoredRun(manifest, childId);
        var nativeChild = new NativeTransport(runtime, output, ordinal)) {
      JsonNode capability =
          nativeChild.send("capabilities", null, NATIVE_REQUEST_DEADLINE_MS).result().path("capabilities");
      assertCapability(capability, manifest);
      assertThat(capability).isEqualTo(result.path("capability"));
      var resumed = new ReplayWorker.ExecutionState("unchanged-before-stored-restore");
      var coordinates =
          child.worker.restoreNativeCheckpoint(
              child.heartbeat, nativeChild, child.run, manifest, child.identity, resumed);
      assertThat(coordinates.tick()).isEqualTo(result.path("prefixTick").asLong());
      assertThat(coordinates.actionIndex()).isEqualTo(result.path("prefixActions").asInt());
      assertThat(nativeChild.requests.get(1).path("method").asText()).isEqualTo("restore");
      assertThat(
              child.worker.executeSourceSchedule(
                  nativeChild,
                  child.heartbeat,
                  child.fence,
                  child.run,
                  resumed,
                  manifest,
                  child.identity,
                  coordinates.actionIndex(),
                  coordinates.tick()))
          .isEqualTo(result.path("finalTick").asLong());
      JsonNode restoredSnapshot =
          nativeChild.send("snapshot", null, NATIVE_REQUEST_DEADLINE_MS).result().path("snapshot");
      JsonNode restoredTrace = nativeChild.send("trace", null, NATIVE_REQUEST_DEADLINE_MS).result();
      // Preserve both sides before assertions, including failed parity diagnostics.
      result.set("restoredSnapshot", restoredSnapshot);
      result.set("restoredTrace", restoredTrace);
      result.set("childRequests", nativeChild.requests.deepCopy());
      result.set("restoredEvidence", resumed.nativeLedger.evidence());
      result.put("childRunId", childId.toString());
      assertThat(restoredSnapshot).isEqualTo(result.path("uninterruptedSnapshot"));
      assertThat(restoredTrace).isEqualTo(result.path("uninterruptedTrace"));
      // Persisted JSON narrows small LongNodes to IntNodes. Require exact canonical bytes,
      // including every value and array position, independently of Java's numeric node class.
      assertThat(t.hashes.canonicalJsonBytes(resumed.nativeLedger.evidence()))
          .containsExactly(t.hashes.canonicalJsonBytes(result.path("uninterruptedEvidence")));
      assertThat(resumed.executedActions).isEqualTo(result.path("finalActions").asInt());
      assertThat(resumed.lastStateHash).isEqualTo(result.path("finalStateHash").asText());
      assertThat(resumed.chainHash).isEqualTo(result.path("finalChainHash").asText());
      assertThat(resumed.lastPersistedOrdinal).isEqualTo(result.path("finalEventOrdinal").asLong());
      var parentSuffix = samples(parentId);
      int prefixSampleCount = result.path("prefixSampleCount").asInt();
      assertThat(samples(childId))
          .isEqualTo(parentSuffix.subList(prefixSampleCount, parentSuffix.size()));
      long prefixNextOrdinal = result.path("prefixNextOrdinal").asLong();
      assertThat(events(childId, prefixNextOrdinal)).isEqualTo(events(parentId, prefixNextOrdinal));
      if (result.path("prefixTerminal").asBoolean())
        for (JsonNode request : nativeChild.requests)
          assertThat(request.path("method").asText()).isNotIn("step", "reset");
      child.finalizeRun(resumed, restoredTrace);
      assertThat(t.runs.findById(childId).orElseThrow().getParentCheckpointId())
          .isEqualTo(UUID.fromString(result.path("checkpointId").asText()));
      assertThat(t.manifests.findById(child.run.getManifestId()).orElseThrow().getPublishState())
          .isEqualTo("DRAFT");
      result.put("finalizedDatabaseArtifactAndFreshNativeParity", true);
    }
  }

  private void assertCapability(JsonNode capability, ObjectNode manifest) {
    assertThat(NativeRunProtocol.negotiated(capability)).isTrue();
    assertThat(capability.path("certifiable").asBoolean()).isFalse();
    assertThat(capability.path("controlAllowed").asBoolean()).isFalse();
    assertThat(capability.path("fidelity").asText()).isEqualTo("SYNTHETIC_CONTRACT");
    for (var field :
        java.util.Map.of(
                "xrspsAdapterVersion",
                "adapterVersion",
                "xrspsBuildCommit",
                "buildCommit",
                "cacheRevision",
                "cacheRevision",
                "contentRevision",
                "contentRevision")
            .entrySet())
      assertThat(capability.path("xrsps").path(field.getValue()))
          .isEqualTo(manifest.path("runtime").path(field.getKey()));
  }

  private List<java.util.Map<String, Object>> samples(UUID id) {
    return t.jdbc.queryForList(
        "SELECT tick,sequence,transition_ordinal,projection_version,state_hash,chain_hash FROM"
            + " state_hash_samples WHERE run_id=? ORDER BY transition_ordinal,tick,sequence",
        id);
  }

  private List<java.util.Map<String, Object>> events(UUID id, long ordinal) {
    return t.jdbc.queryForList(
        "SELECT ordinal,tick,sequence,event_type,category,payload::text AS payload FROM replay_events"
            + " WHERE run_id=? AND ordinal>=? ORDER BY ordinal",
        id,
        ordinal);
  }

  private final class StoredRun implements AutoCloseable {
    static final String OWNER = "authored-native-database-owner";
    final ObjectNode manifest;
    final RuntimeIdentity identity;
    final ReplayRun run;
    final ReplayFence fence;
    final ReplayLeaseHeartbeat.Handle heartbeat;
    final ReplayExpiredAttemptRecoveryService unusedRecovery =
        mock(ReplayExpiredAttemptRecoveryService.class);
    final ReplayWorker worker;

    StoredRun(ObjectNode manifest, UUID existingChild) {
      this.manifest = manifest;
      JsonNode r = manifest.path("runtime");
      identity =
          new RuntimeIdentity(
              r.path("xrspsAdapterVersion").asText(),
              r.path("xrspsBuildCommit").asText(),
              r.path("cacheRevision").asText(),
              r.path("contentRevision").asText());
      UUID id = existingChild;
      if (id == null) {
        var queued = new ReplayRun();
        queued.setManifestId(draftManifest(manifest));
        queued.setManifestChecksum(manifest.path("manifestChecksum").asText());
        queued.setManifestVersion(2);
        queued.setMode(ReplayMode.CAUSAL_SIMULATION);
        queued.setOwnerId(OWNER);
        queued.setIdempotencyKey("authored-native-db-" + UUID.randomUUID());
        queued.setState(ReplayRunState.QUEUED);
        queued.setPhase(ReplayRunPhase.PREFLIGHT);
        queued.setFidelity("SYNTHETIC_CONTRACT");
        queued.setCreatedAt(t.runs.currentTimestamp());
        id = t.runs.saveAndFlush(queued).getId();
      }
      var claim =
          t.runService.claimAtomically(id, "authored-native-db-worker-" + UUID.randomUUID());
      assertThat(claim).isNotNull();
      fence = claim.fence();
      heartbeat = t.heartbeats.start(fence);
      t.persistence.transitionStarting(fence);
      t.persistence.transitionRunning(fence, ReplayRunPhase.EXECUTE);
      t.persistence.storeRuntimeIdentity(fence, identity);
      run = t.runs.findById(id).orElseThrow();
      worker =
          new ReplayWorker(
              t.runs,
              t.attempts,
              t.runService,
              t.manifests,
              t.checkpoints,
              t.refs,
              t.mismatches,
              t.persistence,
              t.heartbeats,
              unusedRecovery,
              t.artifacts,
              new MismatchEngine(t.mismatches, t.hashes),
              t.reportService,
              t.hashes,
              t.properties,
              t.json);
    }

    void finalizeRun(ReplayWorker.ExecutionState state, JsonNode trace) throws Exception {
      worker.captureNativeOutcome(fence, state);
      t.persistence.registerArtifact(
          fence,
          t.artifacts.prepareArtifact(
              run.getId(),
              ArtifactKind.TRACE,
              t.hashes.canonicalJsonBytes(trace),
              "application/json"));
      t.persistence.registerArtifact(
          fence,
          t.artifacts.prepareArtifact(
              run.getId(),
              ArtifactKind.MANIFEST,
              t.hashes.canonicalJsonBytes(manifest),
              "application/json"));
      t.persistence.updatePhase(fence, ReplayRunPhase.EXECUTE, ReplayRunPhase.COMPARE);
      var reservation = t.persistence.reserveSuccessfulFinalization(fence, ReplayOutcome.PASSED);
      var active = t.runs.findById(run.getId()).orElseThrow();
      var attempt = t.attempts.findById(fence.attemptId()).orElseThrow();
      var addresses =
          t.refs.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
              .map(ArtifactRef::getChecksum)
              .toList();
      int count =
          Math.toIntExact(
              t.jdbc.queryForObject(
                  "SELECT count(*) FROM replay_checkpoints WHERE run_id=?",
                  Long.class,
                  run.getId()));
      var prepared =
          t.reportService.prepareReport(
              active,
              attempt,
              ReplayOutcome.PASSED,
              "AUTHORED_NATIVE_DATABASE_CONTINUATION",
              state.executedActions,
              count,
              state.lastStateHash,
              state.chainHash,
              addresses,
              reservation.reservedAt(),
              null);
      t.persistence.registerArtifact(
          fence,
          t.artifacts.prepareArtifact(
              run.getId(), ArtifactKind.REPORT, prepared.canonicalBytes(), "application/json"));
      var receipt = t.artifacts.publishArtifacts(run.getId());
      t.persistence.finalizeSuccessfulRun(
          fence,
          reservation,
          receipt,
          t.reportService.materialize(prepared, active, attempt),
          ReplayOutcome.PASSED);
      assertThat(t.runs.findById(run.getId()).orElseThrow().getState())
          .isEqualTo(ReplayRunState.SUCCEEDED);
      assertThat(t.runs.findById(run.getId()).orElseThrow().getTerminalAt())
          .isEqualTo(reservation.reservedAt());
      assertThat(t.reports.findByRunId(run.getId())).isPresent();
      for (var ref : t.refs.findByRunIdOrderByCreatedAtAsc(run.getId())) {
        assertThat(ref.getState()).isEqualTo(ArtifactRef.STATE_FINALIZED);
        byte[] bytes = t.artifacts.readFinalized(ref);
        assertThat(t.hashes.sha256Hex(bytes)).isEqualTo(ref.getChecksum());
        assertThat((long) bytes.length).isEqualTo(ref.getSizeBytes());
      }
      heartbeat.close();
    }

    @Override
    public void close() {
      heartbeat.close();
      worker.close();
      verifyNoInteractions(unusedRecovery);
    }
  }

  private UUID draftManifest(ObjectNode manifest) {
    // Deliberately inserted below public admission: DRAFT/INCOMPLETE/HELD_OUT, never authority.
    String checksum = manifest.path("manifestChecksum").asText();
    var existing =
        t.jdbc.queryForList(
            "SELECT id FROM scenario_manifests WHERE manifest_checksum=?", UUID.class, checksum);
    if (!existing.isEmpty()) return existing.get(0);
    UUID curation = UUID.randomUUID(), id = UUID.randomUUID();
    String recording = "authored-native-db-" + id;
    t.jdbc.update(
        "INSERT INTO recording_curations(id,recording_id,debug_session_id,version,schema_version,state,role,content_json,content_checksum,created_by,created_at)"
            + " VALUES (?,?,?,1,1,'DRAFT','HELD_OUT',CAST(? AS jsonb),?,'authored-native-db-test',CURRENT_TIMESTAMP)",
        curation,
        recording,
        "authored-native-db-session",
        "{\"origin\":\"AUTHORED_TEST_ONLY\"}",
        checksum);
    t.jdbc.update(
        "INSERT INTO scenario_manifests(id,curation_id,recording_id,debug_session_id,recording_checksum,debug_session_checksum,compiler_version,compiler_schema_version,xrsps_adapter_version,xrsps_build_commit,cache_revision,content_revision,seed,fixed_clock_origin,start_tick,end_tick,manifest_json,manifest_checksum,completeness,publish_state,created_at,manifest_version,fidelity,conversion_quality,action_coverage_pct,gap_count,approximation_count)"
            + " VALUES (?,?,?,?,?,?,'authored-native-db-test',1,?,?,?,?, 'authored',TIMESTAMP '2020-01-01 00:00:00',0,4,CAST(? AS jsonb),?,'INCOMPLETE','DRAFT',CURRENT_TIMESTAMP,2,'SYNTHETIC_CONTRACT','INCOMPLETE',0,1,0)",
        id,
        curation,
        recording,
        "authored-native-db-session",
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

  /** Test-only transport to actual worker-main.ts; no stubbed gameplay responses. */
  private final class NativeTransport extends XrspsKernelProcess {
    private final Process nativeProcess;
    private final BoundedUtf8LineReader reader;
    private final BufferedWriter writer;
    private final java.util.concurrent.ExecutorService reads = Executors.newSingleThreadExecutor();
    private final KernelProtocolCodec codec = new KernelProtocolCodec(t.json);
    private final com.fasterxml.jackson.databind.node.ArrayNode requests = t.json.createArrayNode();
    private int serial;

    NativeTransport(Path runtime, Path output, int ordinal) throws IOException {
      super(t.properties.getXrsps(), t.json);
      Path root = runtime.toAbsolutePath();
      var builder =
          new ProcessBuilder(
                  "node",
                  "--import",
                  "tsx",
                  root.resolve("server/src/replay/worker-main.ts").toString(),
                  "--stdio")
              .directory(root.resolve("server").toFile())
              .redirectError(output.resolve("worker-" + ordinal + ".log").toFile());
      builder.environment().clear();
      for (String key : List.of("PATH", "SystemRoot", "COMSPEC", "TEMP", "TMP", "PATHEXT"))
        if (System.getenv(key) != null) builder.environment().put(key, System.getenv(key));
      builder.environment().put("XRSPS_ALLOW_DEV_IDENTITY", "1");
      builder.environment().put("TSX_DISABLE_CACHE", "1");
      nativeProcess = builder.start();
      reader =
          new BoundedUtf8LineReader(
              nativeProcess.getInputStream(), t.properties.getXrsps().getMaxStdoutLineBytes());
      writer = nativeProcess.outputWriter(StandardCharsets.UTF_8);
    }

    @Override
    public synchronized KernelMessage send(String method, JsonNode params, long timeoutMs) {
      if (timeoutMs <= 0) throw new IllegalArgumentException("Native request deadline expired");
      ObjectNode request =
          t.json.createObjectNode().put("id", Integer.toString(++serial)).put("method", method);
      if (params != null) request.set("params", params);
      requests.add(request.deepCopy());
      try {
        writer.write(t.json.writeValueAsString(request));
        writer.newLine();
        writer.flush();
        var future = reads.submit(reader::readLine);
        BoundedUtf8LineReader.Read read;
        try {
          read = future.get(Math.min(timeoutMs, NATIVE_REQUEST_DEADLINE_MS), TimeUnit.MILLISECONDS);
        } finally {
          if (!future.isDone()) future.cancel(true);
        }
        assertThat(read.status()).isEqualTo(BoundedUtf8LineReader.Status.LINE);
        KernelMessage response = codec.decode(read.line());
        assertThat(response.id()).isEqualTo(request.path("id").asText());
        if (response.error() != null) throw new IOException(response.error().toString());
        return response;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Native request interrupted: " + method, exception);
      } catch (Exception exception) {
        throw new IllegalStateException("Native request failed: " + method, exception);
      }
    }

    @Override
    public KernelMessage send(String method, JsonNode params) {
      return send(method, params, NATIVE_REQUEST_DEADLINE_MS);
    }

    @Override
    public boolean isAlive() {
      return nativeProcess.isAlive();
    }

    @Override
    public void close() {
      try {
        if (nativeProcess.isAlive()) {
          try {
            send("dispose", null, 2000);
          } catch (RuntimeException ignored) {
          }
          nativeProcess.destroy();
          if (!nativeProcess.waitFor(5, TimeUnit.SECONDS)) nativeProcess.destroyForcibly();
          assertThat(nativeProcess.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        reader.close();
        writer.close();
      } catch (Exception exception) {
        nativeProcess.destroyForcibly();
        throw new IllegalStateException("Owned native transport cleanup failed", exception);
      } finally {
        reads.shutdownNow();
        try {
          assertThat(reads.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Owned native reader cleanup interrupted", exception);
        }
        assertThat(nativeProcess.isAlive()).isFalse();
      }
    }
  }
}
