package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-process regressions for the owned execution boundary. Each case asserts actual process
 * liveness, not merely a returned status, so a cancelled or timed-out tool cannot be reported as
 * cleaned up while its process tree is still alive.
 */
class MouseVideoProcessRunnerTest {

  @TempDir Path root;

  private MouseVideoProperties properties;
  private MouseVideoProcessRunner runner;

  @BeforeEach
  void setUp() {
    properties = new MouseVideoProperties();
    properties.setChildShutdownBudgetSeconds(2L);
    runner = new MouseVideoProcessRunner(properties);
  }

  @AfterEach
  void emergencyCleanupAfterAssertions() throws Exception {
    Thread.interrupted();
    try (var files = Files.list(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".pid")).toList()) {
        String value = Files.readString(file).trim();
        if (!value.isEmpty()) {
          ProcessHandle.of(Long.parseLong(value))
              .filter(ProcessHandle::isAlive)
              .ifPresent(ProcessHandle::destroyForcibly);
        }
      }
    }
  }

  /** A scope that reports members until it is stopped, like a busy host's deactivating scope. */
  private static final class SettlingScope implements MouseVideoProcessRunner.Containment {
    private final boolean emptyAfterStop;
    private boolean stopped;

    SettlingScope(boolean emptyAfterStop) {
      this.emptyAfterStop = emptyAfterStop;
    }

    @Override
    public Process start(List<String> command) throws java.io.IOException {
      return new ProcessBuilder("true").start();
    }

    @Override
    public boolean isEmpty() {
      return stopped && emptyAfterStop;
    }

    @Override
    public boolean terminate(long graceMillis, long forceMillis) {
      return false;
    }

    @Override
    public void close() {
      stopped = true;
    }
  }

  @Test
  void aScopeStillSettlingWhenTheToolExitsIsConfirmedByTheProbeAfterStop() throws Exception {
    MouseVideoProcessRunner settling =
        new MouseVideoProcessRunner(properties, () -> new SettlingScope(true));

    MouseVideoProcessRunner.Result result =
        settling.run(List.of("ignored"), 5_000L, new MouseVideoCancellation(), null, 1024);

    assertThat(result.exitCode()).isZero();
  }

  @Test
  void aScopeStillOccupiedAfterStopRemainsUnconfirmed() {
    MouseVideoProcessRunner occupied =
        new MouseVideoProcessRunner(properties, () -> new SettlingScope(false));

    assertThatThrownBy(
            () ->
                occupied.run(List.of("ignored"), 5_000L, new MouseVideoCancellation(), null, 1024))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("could not be confirmed");
  }

  @Test
  void disappearingScopeAndUnreadableCgroupStatesAreInterpretedWithoutFalseAmbiguity() {
    assertThat(MouseVideoProcessRunner.containmentMembership("inactive", "", null, null))
        .isEqualTo(MouseVideoProcessRunner.Membership.EMPTY);
    assertThat(MouseVideoProcessRunner.containmentMembership("", "", null, null))
        .isEqualTo(MouseVideoProcessRunner.Membership.EMPTY);
    assertThat(MouseVideoProcessRunner.containmentMembership("deactivating", "", null, null))
        .isEqualTo(MouseVideoProcessRunner.Membership.EMPTY);
    assertThat(
            MouseVideoProcessRunner.containmentMembership(
                "active", "/cg", null, new java.nio.file.NoSuchFileException("/cg/cgroup.procs")))
        .isEqualTo(MouseVideoProcessRunner.Membership.EMPTY);
    assertThat(
            MouseVideoProcessRunner.containmentMembership("active", "/cg", List.of(" ", ""), null))
        .isEqualTo(MouseVideoProcessRunner.Membership.EMPTY);
    assertThat(MouseVideoProcessRunner.containmentMembership("active", "", null, null))
        .isEqualTo(MouseVideoProcessRunner.Membership.PENDING);
    assertThat(MouseVideoProcessRunner.containmentMembership("activating", "", null, null))
        .isEqualTo(MouseVideoProcessRunner.Membership.PENDING);
    assertThat(
            MouseVideoProcessRunner.containmentMembership(
                "active", "/cg", null, new java.nio.file.AccessDeniedException("/cg/cgroup.procs")))
        .isEqualTo(MouseVideoProcessRunner.Membership.PENDING);
    assertThat(
            MouseVideoProcessRunner.containmentMembership("active", "/cg", List.of("1234"), null))
        .isEqualTo(MouseVideoProcessRunner.Membership.PRESENT);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void unavailableContainmentRefusesBeforeLaunchingTheRequestedCommand() throws Exception {
    MouseVideoProcessRunner unavailable = new MouseVideoProcessRunner(properties, () -> false);
    Path marker = root.resolve("must-not-run");
    assertThatThrownBy(
            () ->
                unavailable.run(
                    List.of("/bin/sh", "-c", "touch '" + marker + "'"),
                    1000L,
                    new MouseVideoCancellation(),
                    null,
                    0))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CLEANUP_UNCONFIRMED);
    assertThat(marker).doesNotExist();
  }

  @Test
  void deathAssertionFailsWithoutKillingASurvivingProcess() throws Exception {
    Process survivor = new ProcessBuilder("/bin/sleep", "60").start();
    try {
      assertThatThrownBy(() -> waitForDeath(survivor.pid(), 20L))
          .isInstanceOf(AssertionError.class);
      assertThat(survivor.isAlive()).isTrue();
    } finally {
      survivor.destroyForcibly();
      assertThat(survivor.waitFor(5L, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void silentProcessHitsIndependentDeadlineAndIsCleanedUp() throws Exception {
    Path pidFile = root.resolve("silent.pid");
    List<String> command = List.of("/bin/sh", "-c", "echo $$ > '" + pidFile + "'; exec sleep 300");
    assertThatThrownBy(() -> runner.run(command, 250L, new MouseVideoCancellation(), null, 0))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_WORKER_TIMEOUT);
    assertThat(isAlive(readPid(pidFile))).isFalse();
  }

  @Test
  void cancellationKillsRootAndTrackedDescendants() throws Exception {
    Path rootPid = root.resolve("tree-root.pid");
    Path childPid = root.resolve("tree-child.pid");
    List<String> command =
        List.of(
            "/bin/sh",
            "-c",
            "echo $$ > '"
                + rootPid
                + "'; sleep 300 & child=$!; echo $child > '"
                + childPid
                + "'; wait $child");
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    Thread canceller =
        new Thread(
            () -> {
              waitForFile(rootPid, 5_000L);
              waitForFile(childPid, 5_000L);
              cancellation.requestCancel();
            },
            "runner-canceller");
    canceller.start();

    assertThatThrownBy(() -> runner.run(command, 60_000L, cancellation, null, 0))
        .isInstanceOf(InterruptedException.class);
    Thread.interrupted();
    canceller.join(5_000L);

    assertThat(isAlive(readPid(rootPid))).isFalse();
    assertThat(isAlive(readPid(childPid))).isFalse();
  }

  @Test
  void floodedOutputIsBoundedAndDoesNotDeadlockCleanup() throws Exception {
    Path pidFile = root.resolve("flood.pid");
    List<String> command = List.of("/bin/sh", "-c", "echo $$ > '" + pidFile + "'; exec yes line");
    AtomicInteger lines = new AtomicInteger();
    MouseVideoProcessRunner.Result result =
        runner.run(
            command,
            10_000L,
            new MouseVideoCancellation(),
            line -> {
              lines.incrementAndGet();
              return false;
            },
            8 * 1024 * 1024);
    assertThat(result.halted()).isTrue();
    assertThat(lines.get()).isLessThanOrEqualTo(1);
    assertThat(isAlive(readPid(pidFile))).isFalse();
  }

  @Test
  void fastParentExitStillStopsOwnedChild() throws Exception {
    Path childPid = root.resolve("fast-child.pid");
    List<String> command =
        List.of("/bin/sh", "-c", "sleep 300 & echo $! > '" + childPid + "'; exit 0");
    MouseVideoProcessRunner.Result result =
        runner.run(command, 10_000L, new MouseVideoCancellation(), null, 0);
    assertThat(result.exitCode()).isZero();
    long pid = readPid(childPid);
    waitForDeath(pid, 5_000L);
    assertThat(isAlive(pid)).isFalse();
  }

  @Test
  void delayedConsumerReceivesEveryLineBeforeReturn() throws Exception {
    properties.setChildShutdownBudgetSeconds(5L);
    List<String> command = List.of("/bin/sh", "-c", "seq 1 2000");
    AtomicInteger lines = new AtomicInteger();
    MouseVideoProcessRunner.Result result =
        runner.run(
            command,
            10_000L,
            new MouseVideoCancellation(),
            line -> {
              lines.incrementAndGet();
              try {
                Thread.sleep(1L);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return true;
            },
            1_000_000);
    assertThat(result.exitCode()).isZero();
    assertThat(result.halted()).isFalse();
    assertThat(result.outputComplete()).isTrue();
    assertThat(lines.get()).isEqualTo(2000);
    Thread.sleep(300L);
    assertThat(lines.get()).isEqualTo(2000);
  }

  @Test
  void callbackFailureIsExplicitAndNeverReportedComplete() throws Exception {
    List<String> command = List.of("/bin/sh", "-c", "seq 1 50");
    MouseVideoProcessRunner.Result result =
        runner.run(
            command,
            10_000L,
            new MouseVideoCancellation(),
            line -> {
              throw new IllegalStateException("consumer failed");
            },
            1_000_000);
    assertThat(result.callbackFailed()).isTrue();
    assertThat(result.halted()).isTrue();
    assertThat(result.outputComplete()).isFalse();
  }

  @Test
  void sessionChangingChildRemainsInsideTheContainment() throws Exception {
    Assumptions.assumeTrue(
        systemdUsable(), "A transient systemd scope is required for cgroup containment");
    Path childPid = root.resolve("session-child.pid");
    List<String> command =
        List.of("/bin/sh", "-c", "setsid sleep 300 & echo $! > '" + childPid + "'; exit 0");
    MouseVideoProcessRunner.Result result =
        runner.run(command, 10_000L, new MouseVideoCancellation(), null, 0);
    assertThat(result.exitCode()).isZero();
    long pid = readPid(childPid);
    waitForDeath(pid, 5_000L);
    assertThat(isAlive(pid)).isFalse();
  }

  private static boolean systemdUsable() throws Exception {
    String unit = "chromascape-test-" + UUID.randomUUID().toString().replace("-", "");
    Process process =
        new ProcessBuilder(
                "systemd-run",
                "--user",
                "--scope",
                "--quiet",
                "--collect",
                "--expand-environment=no",
                "--unit=" + unit,
                "--",
                "/bin/true")
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(5, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      return false;
    }
    boolean usable = process.exitValue() == 0;
    if (!usable) {
      new ProcessBuilder("systemctl", "--user", "stop", unit + ".scope")
          .redirectErrorStream(true)
          .start()
          .waitFor(3, TimeUnit.SECONDS);
    }
    return usable;
  }

  private static void waitForDeath(long pid, long millis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (isAlive(pid) && System.nanoTime() < deadline) {
      Thread.sleep(20L);
    }
    assertThat(isAlive(pid)).as("owned child %s must be dead before teardown", pid).isFalse();
  }

  private static void waitForFile(Path path, long millis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (!Files.isRegularFile(path) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static long readPid(Path path) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Files.isRegularFile(path) && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertThat(Files.isRegularFile(path)).isTrue();
    return Long.parseLong(Files.readString(path).trim());
  }

  private static boolean isAlive(long pid) {
    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
    return handle.map(ProcessHandle::isAlive).orElse(false);
  }
}
