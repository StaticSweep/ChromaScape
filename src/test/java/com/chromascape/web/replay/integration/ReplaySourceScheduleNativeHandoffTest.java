package com.chromascape.web.replay.integration;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.replay.worker.ReplaySourceSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit development transport proof; never bypasses or configures the production launcher. */
@EnabledIfEnvironmentVariable(named = "SC274_NATIVE_SOURCE_EXPORT", matches = ".+")
class ReplaySourceScheduleNativeHandoffTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void unchangedCompilerExportUsesRealJavaSchedulerInTwoGuardedNativeWorkers() throws Exception {
    Path artifact = Path.of(System.getenv("SC274_NATIVE_SOURCE_EXPORT"));
    byte[] bytes = Files.readAllBytes(artifact);
    JsonNode exported = mapper.readTree(bytes), manifest = exported.path("manifest");
    assertThat(exported.path("fixtureOrigin").asText()).isEqualTo("AUTHORED_TEST_ONLY");
    assertThat(exported.path("runtimeIdentitySource").asText()).isEqualTo("ACTUAL_RUNTIME_INPUT");
    assertThat(exported.path("compilerEligibility").asText()).isEqualTo("INCOMPLETE_BLOCKED");
    assertThat(manifest.path("compiler").path("version").asText()).isEqualTo("2.4.0");
    assertThat(manifest.path("execution").path("maximumTicks").asInt()).isEqualTo(32);
    assertThat(manifest.path("actions")).hasSize(3);
    var schedule = new ReplaySourceSchedule(manifest);
    assertThat(schedule.timed()).isTrue();
    var report =
        mapper
            .createObjectNode()
            .put("fixtureOrigin", "AUTHORED_TEST_ONLY")
            .put(
                "exportSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("certification", false)
            .put("genuineRecordingEvidence", false)
            .put("productionLauncherQualified", false);
    var runs = report.putArray("runs");
    JsonNode pending = null, finalCheckpoint = null;
    for (int ordinal = 1; ordinal <= 2; ordinal++) {
      try (var worker = new DiagnosticNativeWorker(ordinal)) {
        var caps = worker.call("capabilities", null).path("capabilities");
        assertThat(caps.path("certifiable").asBoolean()).isFalse();
        assertThat(caps.path("fidelity").asText()).isEqualTo("SYNTHETIC_CONTRACT");
        var runtime = caps.path("xrsps");
        assertThat(manifest.path("runtime").path("contentRevision"))
            .isEqualTo(runtime.path("contentRevision"));
        long tick = manifest.path("recording").path("startTick").asLong();
        if (ordinal == 1) {
          tick =
              worker
                  .call("reset", mapper.createObjectNode().set("manifest", manifest))
                  .path("initialTick")
                  .asLong();
          tick = applySource(worker, schedule, 0, tick);
          assertThat(tick).isEqualTo(16);
          pending = worker.call("snapshot", null).path("snapshot");
        }
        worker.call("restore", mapper.createObjectNode().set("snapshot", pending));
        tick = pending.path("tick").asLong();
        var before = worker.call("snapshot", null);
        var duplicate = schedule.next(0, tick, 10000);
        assertThatThrownBy(() -> worker.call("step", duplicate))
            .hasMessageContaining("SOURCE_ACTION_ORDER_MISMATCH");
        assertThat(worker.call("snapshot", null)).isEqualTo(before);
        for (int index = 1; index < 3; index++) tick = applySource(worker, schedule, index, tick);
        assertThat(tick).isEqualTo(20);
        JsonNode tail;
        while ((tail = schedule.tail(tick, 10000)) != null) {
          long next = worker.call("step", tail).path("terminalTick").asLong();
          assertThat(next)
              .isGreaterThan(tick)
              .isLessThanOrEqualTo(tick + tail.path("maxTicks").asLong());
          tick = next;
        }
        assertThat(tick).isEqualTo(32);
        var observation = worker.call("observe", null).path("observation");
        assertThat(observation.path("player").path("skills").path("fishing").path("xp").asLong())
            .isGreaterThan(4470);
        JsonNode checkpoint = worker.call("snapshot", null).path("snapshot");
        JsonNode runState = checkpoint.path("contentState").get(0).path("runState");
        assertThat(runState.path("status").asText()).isEqualTo("TERMINAL");
        assertThat(runState.path("reason").asText()).isEqualTo("RECORDING_END");
        assertThat(runState.path("elapsedTicks").asInt()).isEqualTo(32);
        assertThat(runState.path("acceptedActions").asInt()).isEqualTo(3);
        assertThatThrownBy(
                () ->
                    worker.call(
                        "step",
                        mapper.createObjectNode().put("expectedTick", 32).put("maxTicks", 1)))
            .hasMessageContaining("RUN_TERMINAL");
        assertThat(worker.call("snapshot", null).path("snapshot")).isEqualTo(checkpoint);
        if (finalCheckpoint != null) assertThat(checkpoint).isEqualTo(finalCheckpoint);
        finalCheckpoint = checkpoint;
        var run =
            runs.addObject()
                .put("ordinal", ordinal)
                .put("finalTick", tick)
                .put("stateChecksum", checkpoint.path("checksum").asText())
                .put("refusalsWithoutMutation", 2)
                .put("crossProcessRestore", ordinal == 2);
        run.set("requests", worker.requests);
        run.set("runtime", runtime);
        run.set("runState", runState);
      }
    }
    assertThat(Files.readAllBytes(artifact)).containsExactly(bytes);
    report.put("identicalFinalCheckpoints", true);
    report.set("pendingCheckpoint", pending);
    report.set("finalCheckpoint", finalCheckpoint);
    var output = Path.of("build", "sc274-contract", "native-source-schedule-handoff.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }

  private long applySource(
      DiagnosticNativeWorker worker, ReplaySourceSchedule schedule, int index, long tick)
      throws Exception {
    for (; ; ) {
      JsonNode params = schedule.next(index, tick, 10000), result = worker.call("step", params);
      long next = result.path("terminalTick").asLong(-1);
      if (params.has("action")) {
        assertThat(result.path("actionApplied").asBoolean()).isTrue();
        assertThat(next).isEqualTo(tick);
        return tick;
      }
      assertThat(next)
          .isGreaterThan(tick)
          .isLessThanOrEqualTo(tick + params.path("maxTicks").asLong());
      tick = next;
    }
  }

  private final class DiagnosticNativeWorker implements AutoCloseable {
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final com.fasterxml.jackson.databind.node.ArrayNode requests = mapper.createArrayNode();
    private int serial;

    DiagnosticNativeWorker(int ordinal) throws Exception {
      Path root = Path.of(System.getenv("SC274_NATIVE_REPLAY_ROOT")).toAbsolutePath();
      Path output =
          Path.of("build", "sc274-contract", "native-source-schedule-worker" + ordinal + ".log")
              .toAbsolutePath();
      Files.createDirectories(output.getParent());
      var builder =
          new ProcessBuilder(
                  "node",
                  "--import",
                  "tsx",
                  root.resolve("server/src/replay/worker-main.ts").toString(),
                  "--stdio")
              .directory(root.resolve("server").toFile())
              .redirectError(output.toFile());
      builder.environment().clear();
      for (String key :
          List.of(
              "PATH",
              "SystemRoot",
              "COMSPEC",
              "TEMP",
              "TMP",
              "PATHEXT",
              "ESBUILD_BINARY_PATH"))
        if (System.getenv(key) != null) builder.environment().put(key, System.getenv(key));
      builder.environment().put("XRSPS_ALLOW_DEV_IDENTITY", "1");
      builder.environment().put("TSX_DISABLE_CACHE", "1");
      process = builder.start();
      reader = process.inputReader(StandardCharsets.UTF_8);
      writer = process.outputWriter(StandardCharsets.UTF_8);
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      var request =
          mapper.createObjectNode().put("id", Integer.toString(++serial)).put("method", method);
      if (params != null) request.set("params", params);
      writer.write(mapper.writeValueAsString(request));
      writer.newLine();
      writer.flush();
      String line = reads.submit((Callable<String>) reader::readLine).get(60, TimeUnit.SECONDS);
      if (line == null) throw new IOException("Diagnostic worker exited before " + method);
      var response = mapper.readTree(line);
      assertThat(response.path("id")).isEqualTo(request.path("id"));
      if (!response.path("ok").asBoolean())
        throw new IOException(response.path("error").toString());
      if (method.equals("step")) requests.add(request);
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
