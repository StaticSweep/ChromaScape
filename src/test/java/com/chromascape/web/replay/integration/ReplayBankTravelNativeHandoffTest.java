package com.chromascape.web.replay.integration;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.replay.worker.ReplaySourceSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in diagnostic transport only; never configures or bypasses the production launcher. */
@EnabledIfEnvironmentVariable(named = "SC274_NATIVE_BANK_TRAVEL_EXPORT", matches = ".+")
class ReplayBankTravelNativeHandoffTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void unchangedCompilerRouteUsesJavaClockAndTwoColdGuardedNativeWorkers() throws Exception {
    Path artifact = Path.of(System.getenv("SC274_NATIVE_BANK_TRAVEL_EXPORT"));
    byte[] bytes = Files.readAllBytes(artifact);
    JsonNode exported = json.readTree(bytes), manifest = exported.path("manifest");
    assertThat(exported.path("fixtureOrigin").asText()).isEqualTo("AUTHORED_TEST_ONLY");
    assertThat(exported.path("runtimeIdentitySource").asText()).isEqualTo("ACTUAL_RUNTIME_INPUT");
    assertThat(exported.path("nativeWorldSource").asText())
        .isEqualTo("ACTUAL_STATIC_NATIVE_INPUTS_NOT_A_RECORDING");
    assertThat(exported.path("compilerEligibility").asText()).isEqualTo("INCOMPLETE_BLOCKED");
    assertThat(manifest.path("compiler").path("version").asText()).isEqualTo("2.4.0");
    assertThat(manifest.path("execution").path("maximumTicks").asInt()).isEqualTo(64);
    assertThat(manifest.path("actions")).hasSize(8);
    assertThat(manifest.path("initialState").path("npcs")).isEmpty();
    assertThat(manifest.path("initialState").path("player").path("inventory")).isEmpty();
    var schedule = new ReplaySourceSchedule(manifest);
    assertThat(schedule.timed()).isTrue();
    ObjectNode report =
        json.createObjectNode()
            .put("fixtureOrigin", "AUTHORED_TEST_ONLY")
            .put(
                "exportSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("certification", false)
            .put("genuineRecordingEvidence", false)
            .put("productionLauncherQualified", false);
    ArrayNode runs = report.putArray("runs");
    JsonNode pending = null, finalCheckpoint = null;
    for (int ordinal = 1; ordinal <= 2; ordinal++) {
      var worker = new DiagnosticNativeWorker(ordinal);
      ObjectNode run = runs.addObject().put("ordinal", ordinal).put("pid", worker.process.pid());
      try (worker) {
        JsonNode caps = worker.call("capabilities", null).path("capabilities");
        assertThat(caps.path("certifiable").asBoolean()).isFalse();
        assertThat(caps.path("fidelity").asText()).isEqualTo("SYNTHETIC_CONTRACT");
        JsonNode runtime = caps.path("xrsps"), pins = manifest.path("runtime");
        assertThat(pins.path("xrspsAdapterVersion")).isEqualTo(runtime.path("adapterVersion"));
        assertThat(pins.path("xrspsBuildCommit")).isEqualTo(runtime.path("buildCommit"));
        assertThat(pins.path("cacheRevision")).isEqualTo(runtime.path("cacheRevision"));
        assertThat(pins.path("contentRevision")).isEqualTo(runtime.path("contentRevision"));
        long tick = manifest.path("recording").path("startTick").asLong();
        assertThat(tick).isZero();
        if (ordinal == 1) {
          tick =
              worker
                  .call("reset", json.createObjectNode().set("manifest", manifest))
                  .path("initialTick")
                  .asLong();
          for (int index = 0; index < 8; index++) {
            tick = applySource(worker, schedule, index, tick);
            assertThat(tick)
                .isEqualTo(manifest.path("actions").get(index).path("sourceActionTick").asLong());
            if (index == 5) {
              JsonNode destination = worker.call("observe", null).path("observation");
              assertThat(destination.path("player").path("position"))
                  .isEqualTo(json.createObjectNode().put("x", 2520).put("y", 3571).put("plane", 0));
              assertBankAndCharge(destination);
            }
          }
          assertThat(tick).isEqualTo(16);
          pending = worker.call("snapshot", null).path("snapshot");
        }
        worker.call("restore", json.createObjectNode().set("snapshot", pending));
        assertThat(worker.call("snapshot", null).path("snapshot")).isEqualTo(pending);
        tick = pending.path("tick").asLong();
        JsonNode before = worker.call("snapshot", null);
        var duplicate = schedule.next(7, tick, 10000);
        assertThatThrownBy(() -> worker.call("step", duplicate))
            .hasMessageContaining("SOURCE_ACTION_ORDER_MISMATCH");
        assertThat(worker.call("snapshot", null)).isEqualTo(before);
        ObjectNode stale = duplicate.deepCopy().put("expectedTick", tick - 1);
        assertThatThrownBy(() -> worker.call("step", stale)).hasMessageContaining("expectedTick");
        assertThat(worker.call("snapshot", null)).isEqualTo(before);
        JsonNode tail;
        while ((tail = schedule.tail(tick, 10000)) != null) {
          long next = worker.call("step", tail).path("terminalTick").asLong();
          assertThat(next)
              .isGreaterThan(tick)
              .isLessThanOrEqualTo(tick + tail.path("maxTicks").asLong());
          tick = next;
        }
        assertThat(tick).isEqualTo(64);
        JsonNode observation = worker.call("observe", null).path("observation");
        assertBankAndCharge(observation);
        assertThat(observation.path("player").path("position"))
            .isEqualTo(json.createObjectNode().put("x", 2517).put("y", 3575).put("plane", 0));
        assertThat(observation.path("player").path("skills").path("fishing").path("xp").asLong())
            .isGreaterThan(4470);
        assertThat(observation.path("player").path("inventory"))
            .anyMatch(item -> List.of(317, 321).contains(item.path("itemId").asInt()));
        JsonNode checkpoint = worker.call("snapshot", null).path("snapshot");
        JsonNode runState = checkpoint.path("contentState").get(0).path("runState");
        assertThat(runState.path("status").asText()).isEqualTo("TERMINAL");
        assertThat(runState.path("reason").asText()).isEqualTo("RECORDING_END");
        assertThat(runState.path("elapsedTicks").asInt()).isEqualTo(64);
        assertThat(runState.path("acceptedActions").asInt()).isEqualTo(8);
        assertThatThrownBy(
                () ->
                    worker.call(
                        "step", json.createObjectNode().put("expectedTick", 64).put("maxTicks", 1)))
            .hasMessageContaining("RUN_TERMINAL");
        assertThat(worker.call("snapshot", null).path("snapshot")).isEqualTo(checkpoint);
        if (finalCheckpoint != null) assertThat(checkpoint).isEqualTo(finalCheckpoint);
        finalCheckpoint = checkpoint;
        run.put("finalTick", tick)
            .put("stateChecksum", checkpoint.path("checksum").asText())
            .put("refusalsWithoutMutation", 3)
            .put("crossProcessRestore", ordinal == 2);
        run.set("requests", worker.requests);
        run.set("runtime", runtime);
        run.set("runState", runState);
        run.set("finalObservation", observation);
      } finally {
        run.put("terminated", !worker.process.isAlive());
      }
    }
    assertThat(Files.readAllBytes(artifact)).containsExactly(bytes);
    report.put("identicalFinalCheckpoints", true);
    report.set("pendingCheckpoint", pending);
    report.set("finalCheckpoint", finalCheckpoint);
    Path output = Path.of("build", "sc274-contract", "native-bank-travel-handoff.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }

  private void assertBankAndCharge(JsonNode observation) {
    assertThat(observation.path("taskState").path("bankOpen").asBoolean()).isFalse();
    assertThat(observation.path("taskState").path("bank").path("slots"))
        .filteredOn(item -> List.of(303, 3867).contains(item.path("itemId").asInt()))
        .hasSize(2)
        .allMatch(item -> item.path("quantity").asInt() == 1);
    assertThat(observation.path("player").path("inventory"))
        .noneMatch(item -> item.path("itemId").asInt() == 3867);
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
    private final ArrayNode requests = json.createArrayNode();
    private int serial;

    DiagnosticNativeWorker(int ordinal) throws Exception {
      Path root = Path.of(System.getenv("SC274_NATIVE_REPLAY_ROOT")).toAbsolutePath();
      Path output =
          Path.of("build", "sc274-contract", "native-bank-travel-worker" + ordinal + ".log")
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
      ObjectNode request =
          json.createObjectNode().put("id", Integer.toString(++serial)).put("method", method);
      if (params != null) request.set("params", params);
      writer.write(json.writeValueAsString(request));
      writer.newLine();
      writer.flush();
      String line = reads.submit((Callable<String>) reader::readLine).get(60, TimeUnit.SECONDS);
      if (line == null) throw new IOException("Diagnostic worker exited before " + method);
      JsonNode response = json.readTree(line);
      assertThat(response.path("id")).isEqualTo(request.path("id"));
      if (method.equals("step")) {
        ObjectNode row = requests.addObject();
        row.set("request", request);
        row.set("response", response);
      }
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
