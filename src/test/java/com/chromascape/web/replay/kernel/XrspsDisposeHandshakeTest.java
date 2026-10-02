package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * REAL child-process shutdown coverage: dispose acknowledgement followed by natural exit, a child
 * that ignores dispose (escalation), and stderr far beyond the logging cap WITHOUT pipe deadlock.
 * The old defects — nanosecond join units, 50-nanosecond polls, and stderr readers returning at the
 * cap — would make these tests hang or leak readers.
 */
class XrspsDisposeHandshakeTest {

  private static final Path FIXTURE_ROOT =
      Path.of("build", "replay-certification", "runtime-fixtures")
          .resolve("XrspsDisposeHandshakeTest-" + UUID.randomUUID())
          .toAbsolutePath();

  private ReplayProperties.Xrsps config(Path workerScript, long shutdownTimeoutMs) {
    ReplayProperties.Xrsps config = new ReplayProperties.Xrsps();
    config.setExecutable(resolvedNodeExecutable());
    config.setEntrypoint(workerScript.toAbsolutePath().toString());
    // Windows ProcessBuilder rejects the short-lived JUnit temp directory form in this fixture
    // path even though Java can access it. Keep the child cwd in the stable worktree while PID and
    // stderr sidecars still use the per-test temp directory.
    config.setWorkingDirectory(Path.of("").toAbsolutePath().toString());
    config.setRequestTimeoutMs(5000);
    config.setShutdownTimeoutMs(shutdownTimeoutMs);
    return config;
  }

  private static String resolvedNodeExecutable() {
    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
      return "node";
    }
    String programFiles = System.getenv("ProgramFiles");
    if (programFiles != null) {
      Path node = Path.of(programFiles, "nodejs", "node.exe");
      if (Files.isRegularFile(node)) {
        return node.toString();
      }
    }
    return "node";
  }

  private static final String ACK_EXIT_WORKER =
      """
            const rl = require('readline').createInterface({ input: process.stdin });
            rl.on('line', (line) => {
              let msg; try { msg = JSON.parse(line); } catch { return; }
              if (msg.method === 'dispose') {
                process.stdout.write(JSON.stringify({ id: msg.id, ok: true, result: { disposed: true } }) + '\\n');
                setTimeout(() => process.exit(0), 100);
              }
            });
            """;

  @Test
  void disposeAcknowledgementIsFollowedByNaturalExitWithinTheBound() throws Exception {
    Path script = writeFixture("ack-exit.js", ACK_EXIT_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 5000);

    long startedAt = System.currentTimeMillis();
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper())) {
      kernel.start();
      assertTrue(kernel.isAlive());
      // Natural exit must happen INSIDE close(); the whole handshake stays bounded.
    }
    long elapsed = System.currentTimeMillis() - startedAt;
    assertTrue(elapsed < 10_000, "shutdown finished within a measured bound (" + elapsed + "ms)");
  }

  private static final String IGNORE_DISPOSE_WORKER =
      """
            const rl = require('readline').createInterface({ input: process.stdin });
            rl.on('line', () => {
              // Deliberately never responds and never exits.
            });
            setTimeout(() => {}, 60000); // keep the event loop alive
            """;

  @Test
  void aChildIgnoringDisposeIsEscalatedWithinTheShutdownDeadline() throws Exception {
    Path script = writeFixture("ignore.js", IGNORE_DISPOSE_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 1500);

    long startedAt = System.currentTimeMillis();
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper())) {
      kernel.start();
      kernel.send("ping", null, 300); // times out; id becomes abandoned
    } catch (com.chromascape.web.replay.error.ReplayException expected) {
      // request timeout is fine; close() still must finish bounded
    }
    long elapsed = System.currentTimeMillis() - startedAt;
    assertTrue(elapsed < 15_000, "escalation completed within bound (" + elapsed + "ms)");
  }

  private static final String STDERR_FLOOD_WORKER =
      """
            const floodChunk = 'x'.repeat(4096);
            for (let i = 0; i < 128; i++) process.stderr.write(floodChunk);
            setInterval(() => process.stderr.write('x'.repeat(4096)), 1);
            const rl = require('readline').createInterface({ input: process.stdin });
            rl.on('line', (line) => {
              let msg; try { msg = JSON.parse(line); } catch { return; }
              process.stdout.write(JSON.stringify({ id: msg.id, ok: true, result: { pong: true } }) + '\\n');
            });
            """;

  @Test
  void stderrBeyondTheCapNeverDeadlocksThePipe() throws Exception {
    Path script = writeFixture("flood.js", STDERR_FLOOD_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 5000);
    cfg.setMaxStderrBytes(64 * 1024);

    ObjectMapper mapper = new ObjectMapper();
    long startedAt = System.currentTimeMillis();
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, mapper)) {
      kernel.start();
      for (int i = 0; i < 20; i++) {
        JsonNode result = kernel.send("ping", mapper.createObjectNode(), 4000).result();
        assertEquals(true, result.path("pong").asBoolean());
      }
      awaitStderrBytes(kernel, cfg.getMaxStderrBytes());
      // Requests kept flowing while stderr flooded far past the cap: no pipe deadlock.
      assertTrue(
          kernel.stderrBytesRead() > cfg.getMaxStderrBytes(),
          "stderr drain must continue consuming bytes after the retention cap");
      assertTrue(
          kernel.stderrPreview().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              <= 8 * 1024,
          "stderr preview must remain bounded by the independent 8 KiB diagnostic limit");
    }
    assertTrue(System.currentTimeMillis() - startedAt < 30_000);
  }

  private static final String SECRET_STDERR_WORKER =
      "process.stderr.write('SECRET_MARKER_7f9b2e\\n');"
          + "process.stderr.write('--profile-email=developer@example.com:pw-value\\n');"
          + "setTimeout(() => process.exit(23), 100);";

  @Test
  void rawStderrSecretNeverEscapesExceptionAndDiagnosticPreviewIsSanitizedAndBounded()
      throws Exception {
    Path script = writeFixture("secret-stderr.js", SECRET_STDERR_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 3000);
    cfg.setMaxStderrBytes(16);

    XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper());
    try {
      kernel.start();
      com.chromascape.web.replay.error.ReplayException failure =
          assertThrows(
              com.chromascape.web.replay.error.ReplayException.class,
              () -> kernel.send("ping", null, 2000));

      String external = String.valueOf(failure.getMessage());
      assertFalse(external.contains("SECRET_MARKER_7f9b2e"));
      assertTrue(external.contains("pid="));
      assertTrue(external.contains("exitCode=23"));
      assertTrue(external.contains("stderrBytes="));
      assertTrue(external.contains("diagnosticRef=xrsps-"));

      awaitStderrBytesAtLeast(
          kernel, "SECRET_MARKER_7f9b2e".getBytes(StandardCharsets.UTF_8).length);
      String preview = kernel.stderrPreview();
      assertFalse(preview.contains("SECRET_MARKER_7f9b2e"));
      assertFalse(preview.contains("developer@example.com"));
      assertFalse(preview.contains("pw-value"));
      assertTrue(preview.contains("[REDACTED]"));
      assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024);
      assertTrue(kernel.stderrBytesRead() > cfg.getMaxStderrBytes());
    } finally {
      kernel.close();
    }
  }

  @Test
  void diagnosticPreviewLimitDoesNotSplitUtf8CodePoints() throws Exception {
    var sanitizer =
        XrspsKernelProcess.class.getDeclaredMethod("sanitizeDiagnosticPreview", String.class);
    sanitizer.setAccessible(true);
    String raw = "a".repeat(8190) + "€" + "tail";

    String preview = (String) sanitizer.invoke(null, raw);

    assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024);
    assertFalse(
        preview.contains("\uFFFD"),
        "preview must not contain a replacement from a split code point");
  }

  private static void awaitStderrBytesAtLeast(XrspsKernelProcess kernel, long expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && kernel.stderrBytesRead() < expected) {
      Thread.sleep(10);
    }
    assertTrue(
        kernel.stderrBytesRead() >= expected,
        () -> "stderr drain did not consume expected bytes: " + kernel.stderrBytesRead());
  }

  /** Waits for the concurrently draining reader to observe the fixture's initial flood. */
  private static void awaitStderrBytes(XrspsKernelProcess kernel, long cap)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && kernel.stderrBytesRead() <= cap) {
      Thread.sleep(10);
    }
    assertTrue(
        kernel.stderrBytesRead() > cap,
        () ->
            "stderr drain did not observe the complete initial flood before the bounded test "
                + "deadline; consumed="
                + kernel.stderrBytesRead()
                + ", cap="
                + cap);
  }

  /**
   * A child that emits an ABANDONED request's response immediately before the dispose
   * acknowledgement must still shut down gracefully: stdout keeps draining through the complete
   * handshake, so the late line is discarded via the abandoned-id set and the ack is consumed. A
   * reader that stopped at {@code shutdownInitiated} would miss the ack and burn the whole shutdown
   * budget before escalating to forcible destruction.
   */
  private static final String LATE_LINE_THEN_ACK_WORKER =
      """
            const rl = require('readline').createInterface({ input: process.stdin });
            let slowId = null;
            rl.on('line', (line) => {
              let msg; try { msg = JSON.parse(line); } catch { return; }
              if (msg.method === 'slow') { slowId = msg.id; return; }
              if (msg.method === 'dispose') {
                if (slowId !== null) {
                  // Late traffic for an already-abandoned request, immediately BEFORE the ack.
                  process.stdout.write(JSON.stringify({ id: slowId, ok: true, result: { late: true } }) + '\\n');
                }
                process.stdout.write(JSON.stringify({ id: msg.id, ok: true, result: { disposed: true } }) + '\\n');
                setTimeout(() => process.exit(0), 100);
              }
            });
            """;

  @Test
  void lateAbandonedResponseBeforeDisposeAckStillShutsDownGracefullyAndBounded() throws Exception {
    Path script = writeFixture("late-then-ack.js", LATE_LINE_THEN_ACK_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 3000);

    long startedAt = System.nanoTime();
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper())) {
      kernel.start();
      try {
        kernel.send("slow", null, 150); // caller leaves; the id becomes abandoned
      } catch (com.chromascape.web.replay.error.ReplayException expectedTimeout) {
        // deliberate
      }
      // close(): the queued late response precedes the ack on the wire.
    }
    long elapsedMs =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    // Graceful path finishes far inside the budget; the broken path burns all 3000ms plus joins.
    assertTrue(elapsedMs < 2_500, "dispose handshake stayed bounded (" + elapsedMs + "ms)");
  }

  // ── ONE global shutdown deadline across the whole process tree ──

  /** Child that ignores graceful termination where the OS allows it (Linux SIGTERM). */
  private static final String IGNORING_CHILD =
      """
            process.on('SIGTERM', () => { /* deliberately ignore graceful destruction */ });
            setInterval(() => {}, 120000);
            """;

  /**
   * Parent that spawns several never-exiting descendants (PIDs written to {@code pidFile} so the
   * test can prove none survives teardown — the Windows no-descendant requirement, checked
   * portably) and itself ignores dispose.
   */
  private static String treeParentScript(Path childScript, Path pidFile) {
    String childPath = childScript.toAbsolutePath().toString().replace("\\", "\\\\");
    String pidPath = pidFile.toAbsolutePath().toString().replace("\\", "\\\\");
    return """
            const { spawn } = require('child_process');
            const fs = require('fs');
            const children = [];
            for (let i = 0; i < 4; i++) {
              children.push(spawn(process.execPath, ['%s'], { stdio: 'ignore' }));
              children[children.length - 1].unref();
            }
            fs.writeFileSync('%s', children.map(c => c.pid).join('\\n'));
            const rl = require('readline').createInterface({ input: process.stdin });
            rl.on('line', () => { /* never responds */ });
            setInterval(() => {}, 120000);
            """
        .formatted(childPath, pidPath);
  }

  /**
   * Asserts every PID recorded in {@code pidFile} is gone — no descendant survives teardown. PIDs
   * are polled briefly: a force-killed child can briefly linger as an unreaped entry on the
   * parent's process table until the JVM reaper collects it, so the check gives the OS a moment
   * before failing.
   */
  private static void assertPidsGone(Path pidFile) throws Exception {
    for (String raw : Files.readString(pidFile).trim().split("\\n")) {
      long pid = Long.parseLong(raw.trim());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean gone = false;
      while (System.nanoTime() < deadline) {
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
          gone = true;
          break;
        }
        Thread.sleep(50);
      }
      assertTrue(gone, () -> "PID " + pid + " must not survive shutdown");
    }
  }

  private static void awaitPidFile(Path pidFile) {
    awaitPidFile(pidFile, null);
  }

  private static void awaitPidFile(Path pidFile, XrspsKernelProcess kernel) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(pidFile)) {
        try {
          boolean hasValidPid =
              Files.readString(pidFile)
                  .lines()
                  .map(String::trim)
                  .anyMatch(XrspsDisposeHandshakeTest::isPositivePid);
          if (hasValidPid) {
            return;
          }
        } catch (IOException ignored) {
          // The fixture may still be replacing the file during startup.
        }
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(
            "Interrupted while waiting for PID file startup: " + pidFile, interrupted);
      }
    }
    String processDiagnostics =
        kernel == null
            ? ""
            : ", processPid="
                + kernel.processPid()
                + ", processExitCode="
                + kernel.processExitCode()
                + ", stderrPreview="
                + kernel.stderrPreview();
    throw new AssertionError(
        "Expected PID file with a valid positive PID: " + pidFile + processDiagnostics);
  }

  private static boolean isPositivePid(String raw) {
    try {
      return Long.parseLong(raw) > 0;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  @Test
  void closeWithMultipleIgnoringDescendantsRespectsOneGlobalDeadline() throws Exception {
    Path child = writeFixture("kernel-ignoring-child.js", IGNORING_CHILD);
    Path pidFile = fixturePath("kernel-tree-pids.txt");
    Path parent = writeFixture("kernel-tree-parent.js", treeParentScript(child, pidFile));
    long shutdownTimeoutMs = 1500;
    ReplayProperties.Xrsps cfg = config(parent, shutdownTimeoutMs);

    long startedAt = System.nanoTime();
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper())) {
      kernel.start();
      awaitPidFile(pidFile, kernel);
      try {
        kernel.send("ping", null, 300); // times out; the tree stays alive for close()
      } catch (com.chromascape.web.replay.error.ReplayException expectedTimeout) {
        // deliberate
      }
    }
    long elapsedMs =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    long bound = shutdownTimeoutMs + XrspsProcessTreeTerminator.FINAL_REAP_ALLOWANCE_MS + 1000;
    assertTrue(
        elapsedMs < bound,
        "total teardown of a 5-process ignoring tree must stay within ONE global deadline"
            + " ("
            + elapsedMs
            + "ms >= bound "
            + bound
            + "ms); per-target timeouts would exceed it");
    // Windows requirement, verified portably: NO descendant PID may remain alive.
    assertPidsGone(pidFile);
  }

  /** The standalone terminator overload creates its own single deadline and delegates. */
  @Test
  void terminatorStandaloneOverloadBoundsTheWholeTreeWithOneDeadline() throws Exception {
    Path child = writeFixture("standalone-ignoring-child.js", IGNORING_CHILD);
    Path pidFile = fixturePath("standalone-pids.txt");
    Path parent = writeFixture("standalone-tree-parent.js", treeParentScript(child, pidFile));

    Process process = new ProcessBuilder("node", parent.toAbsolutePath().toString()).start();
    awaitPidFile(pidFile);
    Thread.sleep(500); // let the descendants spawn
    assertTrue(process.isAlive());

    long startedAt = System.nanoTime();
    XrspsProcessTreeTerminator.terminate(process, 1200);
    long elapsedMs =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertFalse(process.isAlive(), "the parent must not survive termination");
    long bound = 1200 + XrspsProcessTreeTerminator.FINAL_REAP_ALLOWANCE_MS + 1000;
    assertTrue(
        elapsedMs < bound,
        "standalone termination must consume ONE deadline (" + elapsedMs + "ms)");
    assertPidsGone(pidFile);
  }

  // ── malformed dispose acknowledgement ──

  /**
   * Responds to dispose with the WRONG acknowledgement and never exits: graceful path fails closed
   * and the tree escalates within the shared deadline.
   */
  private static final String BAD_DISPOSE_ACK_WORKER =
      """
            const rl = require('readline').createInterface({ input: process.stdin });
            rl.on('line', (line) => {
              let msg; try { msg = JSON.parse(line); } catch { return; }
              if (msg.method === 'dispose') {
                process.stdout.write(JSON.stringify({ id: msg.id, ok: true, result: { disposed: false } }) + '\\n');
                setTimeout(() => {}, 60000); // stay alive: escalation must reap it
              }
            });
            """;

  @Test
  void malformedDisposeAcknowledgementEscalatesWithinTheSharedDeadline() throws Exception {
    Path pidFile = fixturePath("bad-ack-pid.txt");
    Path script = writeFixture("bad-ack.js", pidFilePreamble(pidFile) + BAD_DISPOSE_ACK_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 1500);

    long startedAt = System.nanoTime();
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper())) {
      kernel.start();
      awaitPidFile(pidFile, kernel);
      assertTrue(kernel.isAlive());
    }
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    long bound = 1500 + XrspsProcessTreeTerminator.FINAL_REAP_ALLOWANCE_MS + 1000;
    assertTrue(
        elapsedMs < bound, "malformed ack must escalate within ONE deadline (" + elapsedMs + "ms)");
    assertPidsGone(pidFile);
  }

  /** Prepends a pid-file write so a worker script can prove its process was reaped. */
  private static String pidFilePreamble(Path pidFile) {
    String pidPath = pidFile.toAbsolutePath().toString().replace("\\", "\\\\");
    return "const fs = require('fs');\n"
        + "fs.writeFileSync('"
        + pidPath
        + "', String(process.pid));\n";
  }

  /** Stages every generated fixture and PID path under the bounded build diagnostics directory. */
  private static Path fixturePath(String filename) throws IOException {
    Files.createDirectories(FIXTURE_ROOT);
    Path path = FIXTURE_ROOT.resolve(filename);
    Files.deleteIfExists(path);
    return path;
  }

  private static Path writeFixture(String filename, String content) throws IOException {
    return Files.writeString(fixturePath(filename), content);
  }

  // ── stdout protocol violation ──

  /**
   * Emits a stdout line far beyond the configured cap: a protocol violation that must terminate the
   * tree, never deadlock the reader.
   */
  private static final String OVERSIZED_LINE_WORKER =
      """
            const line = 'x'.repeat(256 * 1024);
            setInterval(() => process.stdout.write(line + '\\n'), 1);
            const rl = require('readline').createInterface({ input: process.stdin });
            rl.on('line', () => { /* never responds */ });
            setTimeout(() => {}, 60000);
            """;

  @Test
  void oversizedStdoutLineIsAProtocolViolationThatTerminatesTheTree() throws Exception {
    Path pidFile = fixturePath("oversized-pid.txt");
    Path script = writeFixture("oversized.js", pidFilePreamble(pidFile) + OVERSIZED_LINE_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 3000);
    cfg.setMaxStdoutLineBytes(4096);

    long startedAt = System.nanoTime();
    XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper());
    kernel.start();
    awaitPidFile(pidFile, kernel);
    assertThrows(
        com.chromascape.web.replay.error.ReplayException.class,
        () -> kernel.send("ping", new ObjectMapper().createObjectNode(), 5000),
        "an oversized stdout line must surface as a protocol failure");
    kernel.close(); // tree already terminated; close() must finish bounded without an ack
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    long bound = 3000 + XrspsProcessTreeTerminator.FINAL_REAP_ALLOWANCE_MS + 2000;
    assertTrue(
        elapsedMs < bound, "protocol-violation teardown stayed bounded (" + elapsedMs + "ms)");
    assertPidsGone(pidFile);
  }

  // ── process already exited before close ──

  /**
   * Exits immediately on load: close() must handle a dead child without hanging. The pid-file
   * preamble already declares fs, so no require is repeated here.
   */
  private static final String EXIT_IMMEDIATELY_WORKER =
      """
            setTimeout(() => process.exit(0), 100);
            """;

  @Test
  void closeHandlesAProcessThatAlreadyExited() throws Exception {
    Path pidFile = fixturePath("exited-pid.txt");
    Path script = writeFixture("exited.js", pidFilePreamble(pidFile) + EXIT_IMMEDIATELY_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 2000);

    long startedAt = System.nanoTime();
    XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper());
    kernel.start();
    awaitPidFile(pidFile, kernel);
    com.chromascape.web.replay.error.ReplayException expectedExit =
        assertThrows(
            com.chromascape.web.replay.error.ReplayException.class,
            () -> kernel.send("ping", null, 1000));
    assertFalse(
        String.valueOf(expectedExit.getMessage()).toLowerCase().contains("unexpected id"),
        "id-less process exit must not be reported as an unexpected response id");
    // Let the process exit and the readers observe EOF.
    Thread.sleep(300);
    kernel.close();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    assertTrue(
        elapsedMs < 5000,
        "close() of an already-exited process finished bounded (" + elapsedMs + "ms)");
    assertPidsGone(pidFile);
  }

  // ── simultaneous closers share one deadline ──

  @Test
  void simultaneousCloseFromTwoThreadsSharesOneDeadline() throws Exception {
    Path pidFile = fixturePath("concurrent-pid.txt");
    Path script =
        writeFixture("ignore-concurrent.js", pidFilePreamble(pidFile) + IGNORE_DISPOSE_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 1500);

    XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper());
    kernel.start();
    awaitPidFile(pidFile, kernel);
    try {
      kernel.send("ping", null, 300);
    } catch (com.chromascape.web.replay.error.ReplayException expectedTimeout) {
      // deliberate: the worker ignores everything
    }

    long startedAt = System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<?>> closers = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        closers.add(
            pool.submit(
                () -> {
                  kernel.close();
                  return null;
                }));
      }
      for (Future<?> closer : closers) {
        closer.get(20, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    long bound = 1500 + XrspsProcessTreeTerminator.FINAL_REAP_ALLOWANCE_MS + 1000;
    assertTrue(
        elapsedMs < bound,
        "two closers must share ONE deadline, not a fresh window each (" + elapsedMs + "ms)");
    assertPidsGone(pidFile);
  }

  // ── interrupted closer ──

  @Test
  void interruptedCloserStillBoundsShutdownAndReapsTheTree() throws Exception {
    Path pidFile = fixturePath("interrupted-pid.txt");
    Path script =
        writeFixture("ignore-interrupted.js", pidFilePreamble(pidFile) + IGNORE_DISPOSE_WORKER);
    ReplayProperties.Xrsps cfg = config(script, 1500);

    XrspsKernelProcess kernel = new XrspsKernelProcess(cfg, new ObjectMapper());
    kernel.start();
    awaitPidFile(pidFile, kernel);
    try {
      kernel.send("ping", null, 300);
    } catch (com.chromascape.web.replay.error.ReplayException expectedTimeout) {
      // deliberate
    }

    long startedAt = System.nanoTime();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread closer =
        new Thread(
            () -> {
              try {
                kernel.close();
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "interrupted-closer");
    closer.start();
    Thread.sleep(150); // let close begin the dispose handshake and block on the ack
    closer.interrupt();
    closer.join(20_000);

    assertFalse(closer.isAlive(), "the interrupted closer must still finish bounded");
    assertNull(failure.get(), "close() must not throw when the closer is interrupted");
    assertTrue(closer.isInterrupted(), "close() must preserve the interrupt status");
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    long bound = 1500 + XrspsProcessTreeTerminator.FINAL_REAP_ALLOWANCE_MS + 2000;
    assertTrue(
        elapsedMs < bound,
        "an interrupted close stays within the shared deadline (" + elapsedMs + "ms)");
    assertPidsGone(pidFile);
  }
}
