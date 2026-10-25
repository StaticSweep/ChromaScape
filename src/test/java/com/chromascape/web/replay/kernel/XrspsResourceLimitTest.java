package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.chromascape.web.replay.config.ReplayProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deliberate resource violations inside the real bubblewrap launch; skipped where unavailable. */
class XrspsResourceLimitTest {
  @TempDir Path directory;

  @BeforeEach
  void requireIsolatedLinuxHost() throws Exception {
    assumeTrue(
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux"));
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/bwrap")));
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit")));
    Process probe = new ProcessBuilder("node", "--version").start();
    assumeTrue(probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0);
    // Unprivileged namespaces must actually work here, not merely the binary exist.
    Process sandbox =
        new ProcessBuilder(
                "/usr/bin/bwrap",
                "--unshare-all",
                "--ro-bind",
                "/usr",
                "/usr",
                "--ro-bind",
                "/lib",
                "/lib",
                "--ro-bind",
                "/lib64",
                "/lib64",
                "/usr/bin/true")
            .start();
    assumeTrue(sandbox.waitFor(10, TimeUnit.SECONDS) && sandbox.exitValue() == 0);
  }

  private Process launch(String script, java.util.function.Consumer<ReplayProperties.Xrsps> tune)
      throws Exception {
    Path entrypoint = directory.resolve("probe.js");
    Files.writeString(entrypoint, script, StandardCharsets.UTF_8);
    var config = new ReplayProperties.Xrsps();
    config.setEntrypoint(entrypoint.toString());
    config.setWorkingDirectory(directory.toString());
    tune.accept(config);
    return XrspsProcessBuilderFactory.create(config, "--stdio").start();
  }

  private static String stdout(Process process) throws Exception {
    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  @Test
  void launchCommandAppliesEveryCeilingBeforeTheSandbox() throws Exception {
    Path entrypoint = directory.resolve("probe.js");
    Files.writeString(entrypoint, "");
    var config = new ReplayProperties.Xrsps();
    config.setEntrypoint(entrypoint.toString());
    config.setWorkingDirectory(directory.toString());
    config.setCpuLimitSeconds(7);
    config.setMaxOpenFiles(321);
    config.setMaxFileBytes(654321);
    config.setMaxHeapMb(123);
    List<String> command = XrspsProcessBuilderFactory.create(config, "--stdio").command();
    assertEquals("/usr/bin/prlimit", command.get(0));
    assertTrue(command.containsAll(List.of("--cpu=7", "--nofile=321", "--fsize=654321", "--core=0")));
    assertTrue(command.indexOf("--") < command.indexOf("/usr/bin/bwrap"));
    assertTrue(command.contains("--max-old-space-size=123"));
    assertTrue(command.indexOf("--max-old-space-size=123") < command.indexOf(entrypoint.toString()));
  }

  @Test
  void cpuExhaustionIsKilledByTheOsCeiling() throws Exception {
    Process process = launch("while (true) {}", config -> config.setCpuLimitSeconds(1));
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CPU ceiling must terminate the worker");
      assertNotEquals(0, process.exitValue());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void heapExhaustionTerminatesTheWorkerInsteadOfGrowingWithoutBound() throws Exception {
    Process process =
        launch(
            "const keep = []; for (;;) keep.push(Buffer.alloc(1024 * 1024).fill(1).toString());",
            config -> config.setMaxHeapMb(64));
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "heap ceiling must terminate the worker");
      assertNotEquals(0, process.exitValue());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void oversizedWriteIsRefusedOrKilled() throws Exception {
    Process process =
        launch(
            "try { require('fs').writeFileSync('/tmp/big', Buffer.alloc(4 * 1024 * 1024));"
                + " console.log('WRITTEN'); } catch (e) { console.log(e.code); }",
            config -> config.setMaxFileBytes(1024 * 1024));
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      String output = stdout(process);
      assertFalse(output.contains("WRITTEN"), output);
      assertTrue(process.exitValue() != 0 || output.contains("EFBIG"), output);
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void descriptorExhaustionIsRefused() throws Exception {
    Process process =
        launch(
            "const fs = require('fs'); let code = 'NONE';"
                + " try { for (let i = 0; i < 400; i++) fs.openSync('/usr/bin/env', 'r'); }"
                + " catch (e) { code = e.code; } console.log(code);",
            config -> config.setMaxOpenFiles(64));
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals("EMFILE", stdout(process).trim());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void cancellationLeavesNoSurvivingWorkerOrChild() throws Exception {
    Process process =
        launch(
            "const { spawn } = require('child_process');"
                + " spawn('/usr/bin/sleep', ['300'], { stdio: 'ignore' });"
                + " setTimeout(() => console.log('READY'), 200); setInterval(() => {}, 1000);",
            config -> {});
    try {
      var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      String line = null;
      while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
        if (line.equals("READY")) break;
      }
      assertEquals("READY", line);
      List<ProcessHandle> tree = process.descendants().toList();
      assertFalse(tree.isEmpty(), "the worker must have a live child to cancel");
      XrspsProcessTreeTerminator.terminate(process, 1000);
      assertFalse(process.isAlive());
      for (ProcessHandle handle : tree) {
        assertFalse(handle.isAlive(), "surviving descendant " + handle.pid());
      }
    } finally {
      process.destroyForcibly();
    }
  }
}
