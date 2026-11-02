package com.chromascape.web.replay.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.manifest.FreshJvmRefusalMain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SC-274 row 3 stability: the refusal matrix must produce byte-identical error payloads in two
 * fresh JVMs. An in-process repetition cannot detect per-JVM state (locale, timezone, hash order,
 * static caches), so the comparison is made across process boundaries, exactly as the determinism
 * lane does for the positive case.
 */
class ScenarioManifestRefusalFreshJvmStabilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long CHILD_TIMEOUT_SECONDS = 300;

  /** The stable refusal the matrix must observe for each injected defect. */
  private static final Map<String, String> EXPECTED_CODES = expectedCodes();

  @Test
  void twoFreshJvmsProduceByteIdenticalRefusalPayloads() throws Exception {
    Path base = Files.createTempDirectory("sc274-refusal-jvm-");
    Path first = Files.createDirectories(base.resolve("process-a"));
    Path second = Files.createDirectories(base.resolve("process-b"));

    Process firstProcess = null;
    Process secondProcess = null;
    try {
      firstProcess =
          ScenarioManifestFreshJvmDeterminismTest.spawn(first, FreshJvmRefusalMain.class.getName());
      secondProcess =
          ScenarioManifestFreshJvmDeterminismTest.spawn(
              second, FreshJvmRefusalMain.class.getName());

      assertTrue(
          firstProcess.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "first refusal JVM timed out; diagnostics: " + first);
      assertTrue(
          secondProcess.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "second refusal JVM timed out; diagnostics: " + second);
      assertEquals(0, firstProcess.exitValue(), log(first));
      assertEquals(0, secondProcess.exitValue(), log(second));

      byte[] firstPayload = Files.readAllBytes(first.resolve("refusals.json"));
      byte[] secondPayload = Files.readAllBytes(second.resolve("refusals.json"));
      assertTrue(firstPayload.length > 0, "first refusal payload was empty");
      assertArrayEquals(firstPayload, secondPayload, "refusal payload bytes differ between JVMs");

      JsonNode firstResult = MAPPER.readTree(first.resolve("result.json").toFile());
      JsonNode secondResult = MAPPER.readTree(second.resolve("result.json").toFile());
      assertEquals(
          firstResult.path("payloadSha256").asText(),
          secondResult.path("payloadSha256").asText(),
          "refusal payload checksums differ");
      assertEquals(
          firstResult.path("compilerVersion").asText(),
          secondResult.path("compilerVersion").asText());
      assertNotEquals(
          firstResult.path("pid").asLong(),
          secondResult.path("pid").asLong(),
          "both refusal runs executed in the same child JVM");
      assertNotEquals(
          ProcessHandle.current().pid(), firstResult.path("pid").asLong(), "ran in the test JVM");

      assertStableCodes(MAPPER.readTree(firstPayload));

      Path evidence = Files.createDirectories(Path.of("build", "sc274-evidence"));
      Files.write(evidence.resolve("refusal-a.json"), firstPayload);
      Files.write(evidence.resolve("refusal-b.json"), secondPayload);
    } finally {
      reap(firstProcess);
      reap(secondProcess);
    }
  }

  private static void assertStableCodes(JsonNode payload) {
    JsonNode refusals = payload.path("refusals");
    Map<String, String> observed = new LinkedHashMap<>();
    for (JsonNode refusal : refusals) {
      observed.put(refusal.path("case").asText(), refusal.path("errorCode").asText());
      assertTrue(
          !refusal.path("safeMessage").asText().isBlank(),
          "case " + refusal.path("case").asText() + " carried no source-linked message");
      assertTrue(refusal.path("httpStatus").asInt() > 0, "case carried no HTTP status");
    }
    assertEquals(EXPECTED_CODES, observed, "the refusal matrix changed its stable codes");
  }

  private static Map<String, String> expectedCodes() {
    Map<String, String> codes = new LinkedHashMap<>();
    codes.put("tick-gap", "TICK_GAP_DETECTED");
    codes.put("action-outside-window", "TICK_RANGE_INVALID");
    codes.put("duplicate-action", "ACTION_SEQUENCE_INVALID");
    codes.put("reordered-action", "ACTION_SEQUENCE_INVALID");
    codes.put("missing-player-state", "PLAYER_STATE_MISSING");
    codes.put("incompatible-cache-revision", "XRSPS_VERSION_INCOMPATIBLE");
    codes.put("incompatible-collision-revision", "SNAPSHOT_INCOMPATIBLE");
    codes.put("missing-region-identity", "SIM_INITIAL_WORLD_INCOMPLETE");
    codes.put("missing-world-identity", "SIM_INITIAL_WORLD_INCOMPLETE");
    return java.util.Collections.unmodifiableMap(codes);
  }

  private static void reap(Process process) {
    if (process == null) return;
    if (process.isAlive()) {
      process.destroy();
      try {
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }

  private static String log(Path output) {
    try {
      return Files.readString(output.resolve("process.log"));
    } catch (Exception error) {
      return "unable to read process log: " + error.getMessage();
    }
  }
}
