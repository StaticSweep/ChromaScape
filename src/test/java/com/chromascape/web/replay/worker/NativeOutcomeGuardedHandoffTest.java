package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Actual guarded native process and production Java source loop; authored input, mocked
 * admission/persistence.
 */
@EnabledIfEnvironmentVariable(named = "SC276_NATIVE_ORCHESTRATION_INPUT", matches = ".+")
class NativeOutcomeGuardedHandoffTest {
  private final ObjectMapper json = new ObjectMapper();
  private final CanonicalProjectionService projection = new CanonicalProjectionService();

  @Test
  void productionAnchoredRestoreMatchesUninterruptedRunningAndTerminalPrefixesInFreshWorkers()
      throws Exception {
    Path input = Path.of(System.getenv("SC276_NATIVE_ORCHESTRATION_INPUT"));
    byte[] inputBytes = Files.readAllBytes(input);
    JsonNode bundle = json.readTree(inputBytes);
    assertThat(bundle.path("origin").asText()).isEqualTo("AUTHORED_TEST_ONLY");
    Path output =
        Path.of(System.getenv("SC276_NATIVE_ORCHESTRATION_OUTPUT")).resolve("anchored-restore");
    Files.createDirectories(output);
    ObjectNode report =
        json.createObjectNode()
            .put("origin", "AUTHORED_TEST_ONLY")
            .put("productionAdmissionQualified", false)
            .put("databaseDurabilityQualified", false)
            .put("compilerPolicyHandoff", false)
            .put("certifiable", false);
    var variants = report.putArray("variants");
    int ordinal = 100;
    for (JsonNode variant : bundle.path("variants")) {
      ObjectNode manifest = (ObjectNode) variant.path("manifest");
      try (var f = new NativeCheckpointRestoreTest.Fixture(manifest);
          var parentProcess = new GuardedWorker(++ordinal, output)) {
        JsonNode capability = parentProcess.call("capabilities", null).path("capabilities");
        assertThat(NativeRunProtocol.negotiated(capability)).isTrue();
        assertThat(capability.path("certifiable").asBoolean()).isFalse();
        assertThat(capability.path("controlAllowed").asBoolean()).isFalse();
        assertThat(capability.path("fidelity").asText()).isEqualTo("SYNTHETIC_CONTRACT");
        doAnswer(
                i ->
                    NativeCheckpointRestoreTest.message(
                        parentProcess.call(i.getArgument(0), i.getArgument(1))))
            .when(f.kernel)
            .send(anyString(), any(), anyLong());
        JsonNode reset =
            parentProcess.call("reset", json.createObjectNode().set("manifest", manifest));
        var es = new ReplayWorker.ExecutionState("sha256:" + "b".repeat(64));
        es.nativeLedger = new NativeRunLedger(manifest);
        es.nativeLedger.acceptReset(reset);
        es.lastStateHash = projection.stateHash(reset.path("initialObservation"));
        long tick = reset.path("initialTick").asLong();
        if (!es.nativeLedger.terminal()) {
          ObjectNode first = new ReplaySourceSchedule(manifest).next(0, tick, 10_000);
          tick =
              f.worker.executeScheduledStep(f.kernel, f.heartbeat, f.fence, f.parent, es, first, 0);
          es.executedActions++;
          if (!es.nativeLedger.terminal()) {
            tick =
                f.worker.executeScheduledStep(
                    f.kernel,
                    f.heartbeat,
                    f.fence,
                    f.parent,
                    es,
                    json.createObjectNode().put("expectedTick", tick).put("maxTicks", 1),
                    1);
            assertThat(tick).isEqualTo(1);
          }
        }
        f.worker.takeCheckpoint(
            f.kernel,
            f.heartbeat,
            f.fence,
            f.parent,
            tick,
            es.executedActions - 1,
            es,
            manifest,
            f.runtime());
        var anchor = f.checkpoint;
        var anchorRef = f.ref;
        JsonNode prefixSnapshot = json.readTree(f.blobs.get(anchorRef.getChecksum()));
        String context = anchor.getRestoreContext();
        long finalTick =
            es.nativeLedger.terminal()
                ? tick
                : f.worker.executeSourceSchedule(
                    f.kernel,
                    f.heartbeat,
                    f.fence,
                    f.parent,
                    es,
                    manifest,
                    f.runtime(),
                    es.executedActions,
                    tick);
        JsonNode complete = parentProcess.call("snapshot", null).path("snapshot");
        JsonNode trace = parentProcess.call("trace", null);
        var result =
            variants
                .addObject()
                .put("name", variant.path("name").asText())
                .put("prefixTick", tick)
                .put("prefixActions", anchor.getActionIndex())
                .put(
                    "prefixTerminal",
                    prefixSnapshot
                        .path("contentState")
                        .get(0)
                        .path("runState")
                        .path("status")
                        .asText()
                        .equals("TERMINAL"));
        result.set("prefixSnapshot", prefixSnapshot);
        result.set("storedContext", json.readTree(context));
        result.set("uninterruptedSnapshot", complete);
        result.set("uninterruptedTrace", trace);
        f.checkpoint = anchor;
        f.ref = anchorRef;
        var child = f.child();
        try (var childProcess = new GuardedWorker(++ordinal, output)) {
          JsonNode childCapability = childProcess.call("capabilities", null).path("capabilities");
          assertThat(childCapability).isEqualTo(capability);
          doAnswer(
                  i ->
                      NativeCheckpointRestoreTest.message(
                          childProcess.call(i.getArgument(0), i.getArgument(1))))
              .when(f.kernel)
              .send(anyString(), any(), anyLong());
          var resumed = new ReplayWorker.ExecutionState("unused-before-restore");
          var coordinates =
              f.worker.restoreNativeCheckpoint(
                  f.heartbeat, f.kernel, child, manifest, f.runtime(), resumed);
          assertThat(coordinates.tick()).isEqualTo(tick);
          assertThat(coordinates.actionIndex()).isEqualTo(anchor.getActionIndex());
          // The restore call is the first state request, not reset or a hidden manifest side
          // channel.
          assertThat(childProcess.requests.get(1).path("method").asText()).isEqualTo("restore");
          var childFence =
              new com.chromascape.web.replay.run.ReplayFence(
                  child.getId(), UUID.randomUUID(), 1, 1, "guarded-child");
          assertThat(
                  f.worker.executeSourceSchedule(
                      f.kernel,
                      f.heartbeat,
                      childFence,
                      child,
                      resumed,
                      manifest,
                      f.runtime(),
                      coordinates.actionIndex(),
                      coordinates.tick()))
              .isEqualTo(finalTick);
          JsonNode restoredSnapshot = childProcess.call("snapshot", null).path("snapshot");
          JsonNode restoredTrace = childProcess.call("trace", null);
          // Save both sides even if parity fails.
          result.set("restoredSnapshot", restoredSnapshot);
          result.set("restoredTrace", restoredTrace);
          result.set("childRequests", childProcess.requests);
          result.set("parentRequests", parentProcess.requests);
          result.set("uninterruptedEvidence", es.nativeLedger.evidence());
          result.set("restoredEvidence", resumed.nativeLedger.evidence());
          Files.writeString(
              output.resolve("variant-" + ordinal + ".json"),
              json.writerWithDefaultPrettyPrinter().writeValueAsString(result),
              StandardOpenOption.CREATE_NEW);
          assertThat(restoredSnapshot).isEqualTo(complete);
          assertThat(restoredTrace).isEqualTo(trace);
          assertThat(resumed.nativeLedger.evidence()).isEqualTo(es.nativeLedger.evidence());
          assertThat(resumed.executedActions).isEqualTo(es.executedActions);
          assertThat(resumed.chainHash).isEqualTo(es.chainHash);
          if (result.path("prefixTerminal").asBoolean())
            for (JsonNode request : childProcess.requests)
              assertThat(request.path("method").asText()).isNotEqualTo("step");
        }
      }
    }
    assertThat(Files.readAllBytes(input)).containsExactly(inputBytes);
    report.put("completeSnapshotsTracesOutcomesAndChainMatch", true);
    Files.writeString(
        output.resolve("anchored-restore.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardOpenOption.CREATE_NEW);
  }

  @Test
  void initialActionAndPerTickOutcomesStopTheProductionJavaLoopInTwoFreshGuardedWorkers()
      throws Exception {
    Path input = Path.of(System.getenv("SC276_NATIVE_ORCHESTRATION_INPUT"));
    byte[] original = Files.readAllBytes(input);
    JsonNode bundle = json.readTree(original);
    assertThat(bundle.path("origin").asText()).isEqualTo("AUTHORED_TEST_ONLY");
    assertThat(bundle.path("variants")).hasSize(3);
    Path output = Path.of(System.getenv("SC276_NATIVE_ORCHESTRATION_OUTPUT"));
    Files.createDirectories(output);
    var report =
        json.createObjectNode()
            .put("origin", "AUTHORED_TEST_ONLY")
            .put("productionAdmissionQualified", false)
            .put("databaseDurabilityQualified", false)
            .put("compilerPolicyHandoff", false)
            .put("certifiable", false)
            .put(
                "inputSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
    var results = report.putArray("variants");
    int ordinal = 0;
    for (JsonNode variant : bundle.path("variants")) {
      ObjectNode manifest = (ObjectNode) variant.path("manifest");
      JsonNode previousSnapshot = null, previousTrace = null;
      for (int repeat = 0; repeat < 2; repeat++) {
        var fixture = new ReplayWorkerNativeOutcomeTest();
        try (var worker = fixture.worker();
            var nativeProcess = new GuardedWorker(++ordinal, output)) {
          JsonNode capability = nativeProcess.call("capabilities", null).path("capabilities");
          assertThat(NativeRunProtocol.negotiated(capability)).isTrue();
          assertThat(capability.path("certifiable").asBoolean()).isFalse();
          assertThat(capability.path("fidelity").asText()).isEqualTo("SYNTHETIC_CONTRACT");
          assertThat(capability.path("controlAllowed").asBoolean()).isFalse();
          assertThat(capability.path("xrsps").path("contentRevision"))
              .isEqualTo(manifest.path("runtime").path("contentRevision"));
          JsonNode reset =
              nativeProcess.call("reset", json.createObjectNode().set("manifest", manifest));
          var es = new ReplayWorker.ExecutionState("sha256:" + "b".repeat(64));
          es.nativeLedger = new NativeRunLedger(manifest);
          es.nativeLedger.acceptReset(reset);
          es.lastStateHash = projection.stateHash(reset.path("initialObservation"));
          when(fixture.kernel.send(anyString(), any(), anyLong()))
              .thenAnswer(
                  invocation ->
                      new KernelMessage(
                          "diagnostic",
                          null,
                          nativeProcess.call(invocation.getArgument(0), invocation.getArgument(1)),
                          null,
                          false,
                          false));
          long tick = fixture.execute(worker, manifest, es);
          assertThat(tick).isEqualTo(variant.path("tick").asLong());
          assertThat(es.executedActions).isEqualTo(variant.path("consumedActions").asInt());
          assertThat(es.nativeLedger.state().path("outcome").asText())
              .isEqualTo(variant.path("outcome").asText());
          assertThat(es.nativeLedger.terminal()).isTrue();
          JsonNode snapshot = nativeProcess.call("snapshot", null).path("snapshot");
          // Preserve mismatched bytes too; a failed assertion must not erase the diagnostic.
          Files.writeString(
              output.resolve("snapshot-" + ordinal + ".json"),
              json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),
              StandardOpenOption.CREATE_NEW);
          es.nativeLedger.requireSnapshot(snapshot);
          ObjectNode unhashed = snapshot.deepCopy();
          unhashed.remove("checksum");
          assertThat(projection.sha256Hex(projection.canonicalJsonBytes(unhashed)))
              .isEqualTo(snapshot.path("checksum").asText());
          assertThat(snapshot.path("contentState").get(0).path("observationHash").asText())
              .isEqualTo(es.lastStateHash);
          JsonNode trace = nativeProcess.call("trace", null);
          assertThatThrownBy(
                  () ->
                      nativeProcess.call(
                          "step",
                          json.createObjectNode().put("expectedTick", tick).put("maxTicks", 1)))
              .hasMessageContaining("RUN_TERMINAL");
          assertThat(nativeProcess.call("snapshot", null).path("snapshot")).isEqualTo(snapshot);
          if (previousSnapshot != null) {
            assertThat(snapshot).isEqualTo(previousSnapshot);
            assertThat(trace).isEqualTo(previousTrace);
          }
          previousSnapshot = snapshot;
          previousTrace = trace;
          ObjectNode result =
              results
                  .addObject()
                  .put("name", variant.path("name").asText())
                  .put("repeat", repeat)
                  .put("tick", tick)
                  .put("consumedActions", es.executedActions);
          result.set("evidence", es.nativeLedger.evidence());
          result.set("requests", nativeProcess.requests);
          result.set("snapshot", snapshot);
          result.set("trace", trace);
          result.set("capability", capability);
          Files.writeString(
              output.resolve("run-" + ordinal + ".json"),
              json.writerWithDefaultPrettyPrinter().writeValueAsString(result),
              StandardOpenOption.CREATE_NEW);
        }
      }
    }
    assertThat(Files.readAllBytes(input)).containsExactly(original);
    report.put("identicalFreshWorkerCheckpointsAndTraces", true);
    Files.writeString(
        output.resolve("handoff.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardOpenOption.CREATE_NEW);
  }

  private final class GuardedWorker implements AutoCloseable {
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final com.fasterxml.jackson.databind.node.ArrayNode requests = json.createArrayNode();
    private int serial;

    GuardedWorker(int ordinal, Path output) throws Exception {
      Path root = Path.of(System.getenv("SC274_NATIVE_REPLAY_ROOT")).toAbsolutePath();
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
      process = builder.start();
      reader = process.inputReader(StandardCharsets.UTF_8);
      writer = process.outputWriter(StandardCharsets.UTF_8);
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      ObjectNode request =
          json.createObjectNode().put("id", Integer.toString(++serial)).put("method", method);
      if (params != null) request.set("params", params);
      writer.write(json.writeValueAsString(request));
      writer.newLine();
      writer.flush();
      String line = reads.submit((Callable<String>) reader::readLine).get(60, TimeUnit.SECONDS);
      if (line == null) throw new IOException("Guarded native worker exited before " + method);
      JsonNode response = json.readTree(line);
      assertThat(response.path("id")).isEqualTo(request.path("id"));
      requests.add(request.deepCopy());
      if (!response.path("ok").asBoolean())
        throw new IOException(response.path("error").toString());
      return response.path("result");
    }

    @Override
    public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
      }
      reads.shutdownNow();
      reader.close();
      writer.close();
      assertThat(reads.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(process.isAlive()).isFalse();
    }
  }
}
