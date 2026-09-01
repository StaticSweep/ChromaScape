package com.chromascape.web.replay.capability;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.kernel.BoundedUtf8LineReader;
import com.chromascape.web.replay.kernel.XrspsProcessBuilderFactory;
import com.chromascape.web.replay.kernel.XrspsProcessTreeTerminator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Performs the xRSPS capability handshake by launching {@code node <entrypoint> --capabilities}
 * through the SHARED process builder, parsing exactly one bounded JSON capability document,
 * verifying configured expected versions, and updating {@link ReplayCapabilityService}.
 *
 * <p>Hardening contract:
 *
 * <ul>
 *   <li>stdout AND stderr are drained CONCURRENTLY by bounded readers started before any wait, so
 *       stderr can never fill the OS pipe and deadlock startup;
 *   <li>the startup deadline is enforced WHILE reads are in progress (never a blocking readLine);
 *   <li>exactly ONE stdout line is accepted; extra protocol noise, oversized lines, malformed
 *       UTF-8, or EOF-before-newline fail validation and destroy the process tree;
 *   <li>timeout destroys descendants; reader tasks are joined before return.
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "replay", name = "enabled", havingValue = "true")
public class XrspsStartupValidator {

  private static final Logger log = LoggerFactory.getLogger(XrspsStartupValidator.class);

  private final ReplayProperties properties;
  private final ReplayCapabilityService capabilityService;
  private final ObjectMapper mapper;

  public XrspsStartupValidator(
      ReplayProperties properties, ReplayCapabilityService capabilityService, ObjectMapper mapper) {
    this.properties = properties;
    this.capabilityService = capabilityService;
    this.mapper = mapper;
  }

  @Scheduled(
      initialDelay = 0,
      fixedDelayString = "#{T(java.time.Duration).ofSeconds(60).toMillis()}")
  public void refreshCapabilities() {
    ReplayProperties.Xrsps cfg = properties.getXrsps();
    if (cfg.getEntrypoint() == null || cfg.getEntrypoint().isBlank()) {
      capabilityService.markUnavailable("xRSPS entrypoint is not configured");
      return;
    }
    Process process = null;
    Thread stdoutTask = null;
    Thread stderrTask = null;
    try {
      process = XrspsProcessBuilderFactory.create(cfg, "--capabilities").start();
      final Process runningProcess = process;
      final ReplayProperties.Xrsps config = cfg;

      // Exactly-one-line acceptance channel; capacity 2 also surfaces extra-noise violations.
      BlockingQueue<String> acceptedLines = new ArrayBlockingQueue<>(2);
      // Cross-thread storage: the reader thread writes, the scheduler thread reads.
      Queue<String> stdoutViolations = new ConcurrentLinkedQueue<>();
      long deadlineNanos =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cfg.getStartupTimeoutMs());

      stdoutTask =
          new Thread(
              () ->
                  drainStdout(
                      runningProcess.getInputStream(), acceptedLines::offer, stdoutViolations::add),
              "xrsps-capabilities-stdout");
      stderrTask =
          new Thread(
              () -> drainStderrBounded(runningProcess.getErrorStream(), config.getMaxStderrBytes()),
              "xrsps-capabilities-stderr");
      stdoutTask.setDaemon(true);
      stderrTask.setDaemon(true);
      stderrTask.start();
      stdoutTask.start();

      String stdoutLine = awaitSingleLine(acceptedLines, stdoutViolations, deadlineNanos);
      boolean exited = process.waitFor(remainingMs(deadlineNanos), TimeUnit.MILLISECONDS);
      if (!exited) {
        // Destroy the tree FIRST: reader threads may still be blocked on open pipes and
        // would only observe EOF after destruction. Joining before termination used to
        // burn the whole join budget per thread on a hung child.
        XrspsProcessTreeTerminator.terminate(process, cfg.getShutdownTimeoutMs());
        joinQuietly(stdoutTask, cfg.getShutdownTimeoutMs());
        joinQuietly(stderrTask, cfg.getShutdownTimeoutMs());
        capabilityService.markUnavailable("capability handshake timed out");
        return;
      }
      XrspsProcessTreeTerminator.terminate(process, cfg.getShutdownTimeoutMs());

      joinQuietly(stdoutTask, cfg.getShutdownTimeoutMs());
      joinQuietly(stderrTask, cfg.getShutdownTimeoutMs());

      if (!stdoutViolations.isEmpty()) {
        capabilityService.markUnavailable(
            "capability stdout violation: " + stdoutViolations.peek());
        return;
      }
      if (process.exitValue() != 0 || stdoutLine == null || stdoutLine.isBlank()) {
        capabilityService.markUnavailable(
            "capability handshake exited with code " + process.exitValue());
        return;
      }

      JsonNode node = mapper.readTree(stdoutLine);
      // STRICT RAW DOCUMENT VALIDATION first: every required field must EXIST with its exact
      // JSON type and an allowed value. A missing field is a problem of its own — it can
      // never inherit a safe-looking default during record conversion.
      List<String> rawProblems = validateRawDocument(node);
      if (!rawProblems.isEmpty()) {
        capabilityService.markUnavailable(String.join("; ", rawProblems));
        return;
      }
      CapabilityManifest manifest = parseManifest(node);

      List<String> versionProblems = verifyVersions(manifest, cfg);
      if (!versionProblems.isEmpty()) {
        capabilityService.markUnavailable(String.join("; ", versionProblems));
        return;
      }
      capabilityService.updateFromHandshake(manifest);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (process != null) {
        XrspsProcessTreeTerminator.terminate(process, cfg.getShutdownTimeoutMs());
      }
      capabilityService.markUnavailable("capability handshake interrupted");
    } catch (Exception e) {
      if (process != null) {
        XrspsProcessTreeTerminator.terminate(process, cfg.getShutdownTimeoutMs());
      }
      log.warn("xRSPS capability handshake failed: {}", e.getMessage());
      capabilityService.markUnavailable("handshake error: " + e.getClass().getSimpleName());
    }
  }

  /** Waits for the single accepted line under the deadline; any violation or noise fails. */
  private String awaitSingleLine(
      BlockingQueue<String> acceptedLines, Queue<String> violations, long deadlineNanos)
      throws InterruptedException {
    while (System.nanoTime() < deadlineNanos) {
      if (!violations.isEmpty()) {
        return null;
      }
      String line = acceptedLines.poll(50, TimeUnit.MILLISECONDS);
      if (line != null) {
        return line;
      }
    }
    return null;
  }

  private static long remainingMs(long deadlineNanos) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
  }

  /** Exactly one bounded line is accepted; anything else records a stable violation. */
  private void drainStdout(
      InputStream raw,
      java.util.function.Consumer<String> accept,
      java.util.function.Consumer<String> violate) {
    try (BoundedUtf8LineReader reader =
        new BoundedUtf8LineReader(raw, properties.getXrsps().getMaxStdoutLineBytes())) {
      boolean accepted = false;
      while (true) {
        BoundedUtf8LineReader.Read read = reader.readLine();
        switch (read.status()) {
          case LINE -> {
            if (accepted) {
              violate.accept("more than one stdout protocol line");
              return;
            }
            accepted = true;
            accept.accept(read.line());
          }
          case OVERSIZED -> {
            violate.accept("oversized stdout line");
            return;
          }
          case MALFORMED_UTF8 -> {
            violate.accept("malformed UTF-8 on stdout");
            return;
          }
          case UNTERMINATED_AT_EOS -> {
            violate.accept("stdout ended without newline termination");
            return;
          }
          case END_OF_STREAM -> {
            return;
          }
          default -> {
            return;
          }
        }
      }
    } catch (Exception ignored) {
      // Stream torn down by the owner.
    }
  }

  /**
   * Concurrent stderr sink with a logging ceiling so the OS pipe can never fill. After the cap,
   * bytes are still read and DISCARDED until EOF: the reader must never return while the child
   * remains alive, or a chatty worker would deadlock the pipe.
   */
  private void drainStderrBounded(InputStream raw, long maxStderrBytes) {
    byte[] chunk = new byte[4096];
    long total = 0;
    boolean breachLogged = false;
    try {
      int read;
      while ((read = raw.read(chunk)) != -1) {
        total += read;
        if (!breachLogged && total > maxStderrBytes) {
          breachLogged = true;
          log.warn(
              "xRSPS capability stderr exceeded max bytes; discarding further output until EOF");
        }
      }
    } catch (IOException ignored) {
      // Stream torn down by the owner.
    }
  }

  private static void joinQuietly(Thread thread, long timeoutMs) {
    if (thread == null) {
      return;
    }
    try {
      thread.join(timeoutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Strict raw-document validation, run BEFORE any record conversion. Every required field must
   * EXIST with its exact JSON type and an allowed value; anything missing, null, wrongly typed,
   * forbidden-true, or unsupported fails the handshake into an UNAVAILABLE capability.
   */
  List<String> validateRawDocument(JsonNode node) {
    List<String> problems = new ArrayList<>();
    if (node == null || !node.isObject()) {
      problems.add("capability document must be a JSON object");
      return problems;
    }
    requireIntField(problems, node, "schemaVersion", 1);
    requireBoolField(problems, node, "certifiable", true);
    requireBoolField(problems, node, "controlAllowed", false);
    requireBoolField(problems, node, "openRuneAvailable", false);

    JsonNode modes = node.get("modes");
    if (modes == null || !modes.isObject()) {
      problems.add("modes is missing or not an object");
    } else {
      // Observed playback is ChromaScape's read-only API, never the simulation worker.
      requireModesBool(problems, modes, "observedPlayback", false);
      requireModesBool(problems, modes, "policyReplay", false);
      requireModesBool(problems, modes, "causalSimulation", true);
    }

    requireExactIntVersionArray(problems, node, "scenarioSchemaVersions");
    requireExactIntVersionArray(problems, node, "semanticActionVersions");
    requireExactIntVersionArray(problems, node, "snapshotVersions");
    requireExactIntVersionArray(problems, node, "projectionVersions");
    requireExactHashVersionArray(problems, node);
    requireFidelity(problems, node);
    requireExactSupportedActions(problems, node);

    JsonNode xrsps = node.get("xrsps");
    if (xrsps == null || !xrsps.isObject()) {
      problems.add("xrsps is missing or not an object");
      return problems;
    }
    requireIdentityText(problems, xrsps, "adapterVersion");
    requireIdentityText(problems, xrsps, "buildCommit");
    for (String revision : new String[] {"cacheRevision", "contentRevision"}) {
      JsonNode field = xrsps.get(revision);
      if (field == null || field.isNull()) {
        problems.add("xrsps." + revision + " is missing");
      } else if (!field.isTextual()) {
        problems.add("xrsps." + revision + " must be a string");
      } else if (!SHA256_ADDRESS.matcher(field.asText()).matches()) {
        problems.add("xrsps." + revision + " is not a sha256 content address");
      }
    }
    return problems;
  }

  /** Fidelity provenance is a required closed-set field; it is never inferred from Git identity. */
  private void requireFidelity(List<String> problems, JsonNode node) {
    JsonNode field = node.get("fidelity");
    if (field == null || field.isNull()) {
      problems.add("fidelity is missing");
    } else if (!field.isTextual()) {
      problems.add("fidelity must be a string");
    } else {
      try {
        CapabilityManifest.Fidelity.valueOf(field.asText());
      } catch (IllegalArgumentException e) {
        problems.add("fidelity=" + field.asText() + " is unsupported");
      }
    }
  }

  /** The current worker's declared action surface is an exact, duplicate-free closed set. */
  private void requireExactSupportedActions(List<String> problems, JsonNode node) {
    JsonNode array = node.get("supportedActions");
    if (array == null || array.isNull()) {
      problems.add("supportedActions is missing");
      return;
    }
    if (!array.isArray()) {
      problems.add("supportedActions must be an array");
      return;
    }
    if (array.size() != CapabilityManifest.V1_SUPPORTED_ACTIONS.size()) {
      problems.add("supportedActions must be exactly " + CapabilityManifest.V1_SUPPORTED_ACTIONS);
      return;
    }
    for (int i = 0; i < array.size(); i++) {
      JsonNode action = array.get(i);
      if (!action.isTextual()
          || !CapabilityManifest.V1_SUPPORTED_ACTIONS.get(i).equals(action.asText())) {
        problems.add("supportedActions must be exactly " + CapabilityManifest.V1_SUPPORTED_ACTIONS);
        return;
      }
    }
  }

  private void requireIntField(List<String> problems, JsonNode node, String name, int expected) {
    JsonNode field = node.get(name);
    if (field == null || field.isNull()) {
      problems.add(name + " is missing");
    } else if (!field.isInt()) {
      problems.add(name + " must be an integer");
    } else if (field.asInt() != expected) {
      problems.add(name + "=" + field.asInt() + " is unsupported; expected " + expected);
    }
  }

  private void requireBoolField(
      List<String> problems, JsonNode node, String name, boolean expected) {
    JsonNode field = node.get(name);
    if (field == null || field.isNull()) {
      problems.add(name + " is missing");
    } else if (!field.isBoolean()) {
      problems.add(name + " must be a boolean");
    } else if (field.asBoolean() != expected) {
      problems.add(name + "=" + field.asBoolean() + " is forbidden; expected " + expected);
    }
  }

  /** Expected == null accepts either boolean value (used only for observedPlayback). */
  private void requireModesBool(
      List<String> problems, JsonNode modes, String name, Boolean expected) {
    JsonNode field = modes.get(name);
    if (field == null || field.isNull()) {
      problems.add("modes." + name + " is missing");
    } else if (!field.isBoolean()) {
      problems.add("modes." + name + " must be a boolean");
    } else if (expected != null && field.asBoolean() != expected.booleanValue()) {
      problems.add(
          "modes." + name + "=" + field.asBoolean() + " is forbidden; expected " + expected);
    }
  }

  /** Version arrays must exist and contain EXACTLY the single supported version [1]. */
  private void requireExactIntVersionArray(List<String> problems, JsonNode node, String name) {
    JsonNode array = node.get(name);
    if (array == null || array.isNull()) {
      problems.add(name + " is missing");
      return;
    }
    if (!array.isArray()) {
      problems.add(name + " must be an array");
      return;
    }
    if (array.size() != 1 || !array.get(0).isInt() || array.get(0).asInt() != 1) {
      StringBuilder actual = new StringBuilder("[");
      for (JsonNode v : array) {
        actual.append(v.isInt() ? v.asInt() : v.toString()).append(',');
      }
      problems.add(name + " must be exactly [1] but was " + actual.append(']'));
    }
  }

  /** Hash versions must contain exactly the supported v1 hash. */
  private void requireExactHashVersionArray(List<String> problems, JsonNode node) {
    String supported = "sha256-canonical-json-v1";
    JsonNode array = node.get("hashVersions");
    if (array == null || array.isNull()) {
      problems.add("hashVersions is missing");
      return;
    }
    if (!array.isArray()) {
      problems.add("hashVersions must be an array");
      return;
    }
    if (array.size() != 1
        || !array.get(0).isTextual()
        || !supported.equals(array.get(0).asText())) {
      StringBuilder actual = new StringBuilder("[");
      for (JsonNode v : array) {
        actual.append(v.isTextual() ? '"' + v.asText() + '"' : v.toString()).append(',');
      }
      problems.add(
          "hashVersions must contain exactly [\""
              + supported
              + "\"] but was "
              + actual.append(']'));
    }
  }

  /** Identity text fields must exist, be strings, and never be blank or dev placeholders. */
  private void requireIdentityText(List<String> problems, JsonNode xrsps, String name) {
    JsonNode field = xrsps.get(name);
    if (field == null || field.isNull()) {
      problems.add("xrsps." + name + " is missing");
      return;
    }
    if (!field.isTextual()) {
      problems.add("xrsps." + name + " must be a string");
      return;
    }
    String value = field.asText();
    if (value.isBlank() || "dev".equals(value) || "headless-deterministic".equals(value)) {
      problems.add("xrsps." + name + " is blank or carries a non-certifiable placeholder");
    }
  }

  CapabilityManifest parseManifest(JsonNode node) {
    CapabilityManifest.CapabilityModes modes =
        new CapabilityManifest.CapabilityModes(
            node.path("modes").path("observedPlayback").asBoolean(false),
            node.path("modes").path("policyReplay").asBoolean(false),
            node.path("modes").path("causalSimulation").asBoolean(false));
    JsonNode xrspsNode = node.path("xrsps");
    CapabilityManifest.XrspsIdentity identity =
        new CapabilityManifest.XrspsIdentity(
            xrspsNode.path("adapterVersion").asText(""),
            xrspsNode.path("buildCommit").asText(""),
            xrspsNode.path("cacheRevision").asText(""),
            xrspsNode.path("contentRevision").asText(""));
    // The worker's own attestation is parsed verbatim: a MISSING certifiable field is false.
    // controlAllowed/openRuneAvailable are likewise taken from the worker so a worker that
    // advertises forbidden capabilities fails validation instead of being silently masked.
    return new CapabilityManifest(
        node.path("schemaVersion").asInt(-1),
        node.path("controlAllowed").asBoolean(false),
        modes,
        intList(node.path("scenarioSchemaVersions")),
        intList(node.path("semanticActionVersions")),
        intList(node.path("snapshotVersions")),
        intList(node.path("projectionVersions")),
        stringList(node.path("hashVersions")),
        identity,
        node.path("openRuneAvailable").asBoolean(false),
        node.path("certifiable").asBoolean(false),
        parseFidelity(node.path("fidelity")),
        stringList(node.path("supportedActions")));
  }

  private static CapabilityManifest.Fidelity parseFidelity(JsonNode field) {
    if (field == null || !field.isTextual()) {
      return null;
    }
    try {
      return CapabilityManifest.Fidelity.valueOf(field.asText());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static final Pattern SHA256_ADDRESS = Pattern.compile("^sha256:[0-9a-f]{64}$");

  List<String> verifyVersions(CapabilityManifest manifest, ReplayProperties.Xrsps cfg) {
    List<String> problems = new ArrayList<>();
    if (manifest.schemaVersion() != 1) {
      problems.add("unsupported capability schemaVersion " + manifest.schemaVersion());
    }
    if (!manifest.certifiable()) {
      problems.add("worker did not attest a certifiable build");
    }
    if (manifest.controlAllowed()) {
      problems.add("control must be disallowed");
    }
    if (manifest.openRuneAvailable()) {
      problems.add("OpenRune must be unavailable");
    }
    if (manifest.modes() == null
        || manifest.modes().observedPlayback()
        || manifest.modes().policyReplay()) {
      problems.add("worker playback/policy replay modes must be unavailable in v1");
    }
    if (manifest.modes() == null || !manifest.modes().causalSimulation()) {
      problems.add("causal simulation mode is required");
    }
    if (manifest.fidelity() == null) {
      problems.add("fidelity is missing or unsupported");
    }
    if (!CapabilityManifest.V1_SUPPORTED_ACTIONS.equals(manifest.supportedActions())) {
      problems.add("supportedActions must be exactly " + CapabilityManifest.V1_SUPPORTED_ACTIONS);
    }
    requireNonPlaceholder(problems, "adapterVersion", manifest.xrsps().adapterVersion(), false);
    requireNonPlaceholder(problems, "buildCommit", manifest.xrsps().buildCommit(), false);
    requireRevision(problems, "cacheRevision", manifest.xrsps().cacheRevision());
    requireRevision(problems, "contentRevision", manifest.xrsps().contentRevision());
    requireExpected(
        problems,
        "adapterVersion",
        cfg.getExpectedAdapterVersion(),
        manifest.xrsps().adapterVersion());
    requireExpected(
        problems, "buildCommit", cfg.getExpectedBuildCommit(), manifest.xrsps().buildCommit());
    requireExpected(
        problems,
        "cacheRevision",
        cfg.getExpectedCacheRevision(),
        manifest.xrsps().cacheRevision());
    requireExpected(
        problems,
        "contentRevision",
        cfg.getExpectedContentRevision(),
        manifest.xrsps().contentRevision());
    return problems;
  }

  /** Identity fields must be present, non-blank, and never the development placeholders. */
  private void requireNonPlaceholder(
      List<String> problems, String name, String actual, boolean sha256Address) {
    if (actual == null || actual.isBlank()) {
      problems.add(name + " is missing or blank");
      return;
    }
    if ("dev".equals(actual) || "headless-deterministic".equals(actual)) {
      problems.add(name + " carries a non-certifiable development placeholder");
      return;
    }
    if (sha256Address && !SHA256_ADDRESS.matcher(actual).matches()) {
      problems.add(name + " is not a sha256 content address");
    }
  }

  private void requireRevision(List<String> problems, String name, String actual) {
    requireNonPlaceholder(problems, name, actual, true);
  }

  private void requireExpected(List<String> problems, String name, String expected, String actual) {
    if (expected != null && !expected.isBlank() && !expected.equals(actual)) {
      problems.add(name + " mismatch: expected " + expected + ", got " + actual);
    }
  }

  private static List<Integer> intList(JsonNode array) {
    List<Integer> out = new ArrayList<>();
    if (array.isArray()) {
      for (JsonNode n : array) {
        out.add(n.asInt());
      }
    }
    return out;
  }

  private static List<String> stringList(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array.isArray()) {
      for (JsonNode n : array) {
        out.add(n.asText());
      }
    }
    return out;
  }
}
