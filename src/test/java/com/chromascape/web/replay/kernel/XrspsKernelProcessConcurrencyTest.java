package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Regression coverage for the one-request-at-a-time xRSPS stdio correlation contract. */
class XrspsKernelProcessConcurrencyTest {

  private static final Path FIXTURE_ROOT =
      Path.of("build", "replay-certification", "runtime-fixtures")
          .resolve("XrspsKernelProcessConcurrencyTest-" + UUID.randomUUID())
          .toAbsolutePath();

  /**
   * A deliberately slow response makes two unsynchronised Java callers overlap. The production
   * worker is allowed to process one request at a time; the Java wrapper must therefore serialize
   * the complete write/response transaction rather than only synchronizing stdin writes.
   */
  private static final String SERIAL_WORKER =
      """
            const fs = require('fs');
            const readline = require('readline');
            const pidPath = '__PID_PATH__';
            const stderrPath = '__STDERR_PATH__';
            fs.writeFileSync(pidPath, String(process.pid));
            fs.writeFileSync(stderrPath, 'fixture-started\\n');
            const rl = readline.createInterface({ input: process.stdin });
            // Keep the fixture alive before the first request reaches the pipe. Node may not keep
            // a Windows stdin pipe referenced while no line callback is pending; the real worker
            // has its protocol loop and runtime handles, so make that lifecycle explicit here.
            process.stdin.resume();
            setInterval(() => {}, 2147483647);
            rl.on('line', (line) => {
              let request;
              try { request = JSON.parse(line); } catch (error) {
                process.stderr.write('parse failure: ' + JSON.stringify(line) + ' ' + error + '\\n');
                process.exit(2);
              }
              const delay = request.method === 'first' ? 120 : 0;
              setTimeout(() => {
                process.stdout.write(JSON.stringify({ id: request.id, ok: true,
                  result: { method: request.method } }) + '\\n');
              }, delay);
            });
            """;

  @Test
  void concurrentSendersCannotConsumeEachOthersResponses() throws Exception {
    Path fixtureRoot = FIXTURE_ROOT;
    Files.createDirectories(fixtureRoot);
    Path pidFile = fixtureRoot.resolve("serial-worker.pid");
    Path stderrFile = fixtureRoot.resolve("serial-worker.stderr");
    Files.deleteIfExists(pidFile);
    Files.deleteIfExists(stderrFile);
    Path worker =
        Files.writeString(
            fixtureRoot.resolve("serial-worker.js"), serialWorker(pidFile, stderrFile));
    ReplayProperties.Xrsps config = new ReplayProperties.Xrsps();
    config.setExecutable(resolvedNodeExecutable());
    config.setEntrypoint(worker.toAbsolutePath().toString());
    // Use the stable repository cwd for this Windows fixture. The JUnit temporary directory is
    // deliberately retained as the script path, while ProcessBuilder's cwd stays outside the
    // short-lived temp tree used by the Windows host bridge.
    config.setWorkingDirectory(Path.of("").toAbsolutePath().toString());
    config.setRequestTimeoutMs(5000);
    config.setShutdownTimeoutMs(3000);

    ExecutorService callers = Executors.newFixedThreadPool(2);
    try (XrspsKernelProcess kernel = new XrspsKernelProcess(config, new ObjectMapper())) {
      // The PID file is only a bounded startup synchronization point. It keeps this test from
      // racing the Windows child bootstrap and preserves the generated inputs for diagnostics.
      kernel.start();
      awaitPidFile(pidFile, kernel);
      List<Callable<String>> requests =
          List.of(
              () -> kernel.send("first", null, 5000).result().path("method").asText(),
              () -> kernel.send("second", null, 5000).result().path("method").asText());
      List<Future<String>> futures = new ArrayList<>();
      for (Callable<String> request : requests) {
        futures.add(callers.submit(request));
      }

      assertEquals("first", futures.get(0).get(10, TimeUnit.SECONDS));
      assertEquals("second", futures.get(1).get(10, TimeUnit.SECONDS));
      assertTrue(kernel.isAlive());
    } finally {
      callers.shutdownNow();
    }
  }

  private static String serialWorker(Path pidFile, Path stderrFile) {
    return SERIAL_WORKER
        .replace("__PID_PATH__", js(pidFile))
        .replace("__STDERR_PATH__", js(stderrFile));
  }

  private static String js(Path path) {
    return path.toString().replace("\\", "\\\\").replace("'", "\\'");
  }

  private static void awaitPidFile(Path pidFile, XrspsKernelProcess kernel) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(pidFile)) {
        try {
          if (Files.readString(pidFile).trim().matches("[1-9][0-9]*")) {
            return;
          }
        } catch (IOException ignored) {
          // The fixture can be replacing the sidecar during startup.
        }
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for fixture startup", interrupted);
      }
    }
    throw new AssertionError(
        "fixture did not create its PID file: "
            + pidFile
            + ", processPid="
            + kernel.processPid()
            + ", processExitCode="
            + kernel.processExitCode()
            + ", stderrPreview="
            + kernel.stderrPreview()
            + ", script="
            + pidFile.getParent().resolve("serial-worker.js"));
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
}
