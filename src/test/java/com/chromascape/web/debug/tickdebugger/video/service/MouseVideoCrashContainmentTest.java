package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Abrupt JVM death must not leave decoder descendants or a live workspace mount behind. */
class MouseVideoCrashContainmentTest {
  @TempDir Path root;

  @Test
  void abruptJvmDeathStopsItsScopeAndWorkspaceServer() throws Exception {
    assumeTrue(MouseVideoWorkspace.toolsAvailable());
    var entries = new LinkedHashSet<String>();
    entries.add(System.getProperty("java.class.path"));
    for (ClassLoader loader = getClass().getClassLoader();
        loader != null;
        loader = loader.getParent()) {
      if (loader instanceof java.net.URLClassLoader urls) {
        for (var url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
      }
    }
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                String.join(java.io.File.pathSeparator, entries),
                getClass().getName(),
                root.toString())
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("crash.log").toFile())
            .start();
    List<ProcessHandle> descendants = new ArrayList<>();
    List<String> units = new ArrayList<>();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!Files.exists(root.resolve("jobs/crash/ready"))
          && child.isAlive()
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertThat(Files.exists(root.resolve("jobs/crash/ready")))
          .withFailMessage(Files.readString(root.resolve("crash.log")))
          .isTrue();
      descendants = child.descendants().toList();
      assertThat(descendants).isNotEmpty();
      for (ProcessHandle process : descendants) {
        for (String argument : process.info().arguments().orElse(new String[0])) {
          if (argument.startsWith("--unit=chromascape-video-")) {
            units.add(argument.substring("--unit=".length()) + ".scope");
          }
        }
        try {
          String cgroup =
              Files.readString(Path.of("/proc", Long.toString(process.pid()), "cgroup"));
          var match =
              java.util.regex.Pattern.compile("chromascape-video-[a-f0-9]+\\.scope")
                  .matcher(cgroup);
          if (match.find() && !units.contains(match.group())) units.add(match.group());
        } catch (java.io.IOException exited) {
          // A transient helper may already have exited.
        }
      }
      assertThat(units).isNotEmpty();
      child.destroyForcibly();
      assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (descendants.stream().anyMatch(MouseVideoCrashContainmentTest::active)
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertThat(
              descendants.stream()
                  .filter(MouseVideoCrashContainmentTest::active)
                  .map(ProcessHandle::pid)
                  .toList())
          .isEmpty();
      assertThat(MouseVideoWorkspace.isBoundedMount(root.resolve("jobs/crash"), 32L * 1024 * 1024))
          .isFalse();
      assertThat(Files.exists(root.resolve("work-volumes/crash.ext2"))).isTrue();
      MouseVideoProperties properties = new MouseVideoProperties();
      properties.setDirectory(root.toString());
      assertThat(
              MouseVideoWorkspace.sweepUnmounted(
                  properties, java.time.Instant.now().plusSeconds(1)))
          .isEqualTo(1);
    } finally {
      child.destroyForcibly();
      for (String unit : units) {
        new ProcessBuilder("systemctl", "--user", "stop", unit)
            .start()
            .waitFor(5, TimeUnit.SECONDS);
      }
      for (ProcessHandle process : descendants) {
        if (process.isAlive()) process.destroyForcibly();
      }
      new ProcessBuilder("fusermount3", "-u", root.resolve("jobs/crash").toString())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor(5, TimeUnit.SECONDS);
    }
  }

  public static void main(String[] args) throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(args[0]);
    properties.setRestrictedLauncher(true);
    properties.setMaxJobDiskBytes(64L * 1024 * 1024);
    try (var volume = MouseVideoWorkspace.open(properties, "crash")) {
      var launcher = new MouseVideoRestrictedLauncher(properties);
      var runner = new MouseVideoProcessRunner(properties, launcher);
      runner.run(
          List.of(
              "/bin/sh",
              "-c",
              "echo ready > " + volume.path().resolve("ready") + "; exec sleep 300"),
          300_000,
          new MouseVideoCancellation(),
          null,
          0);
    }
  }

  private static boolean active(ProcessHandle process) {
    if (!process.isAlive()) return false;
    try {
      String stat = Files.readString(Path.of("/proc", Long.toString(process.pid()), "stat"));
      return stat.charAt(stat.lastIndexOf(')') + 2) != 'Z';
    } catch (java.io.IOException gone) {
      return false;
    }
  }
}
