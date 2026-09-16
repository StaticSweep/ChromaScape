package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxProcessManagerTest {

  private static final Instant T1 = Instant.ofEpochMilli(1_000L);
  private static final Instant T2 = Instant.ofEpochMilli(2_000L);
  private static final long STAT_START_TICKS = 12345L;

  @TempDir Path root;

  @BeforeEach
  void createSyntheticHostFiles() throws IOException {
    // /proc/uptime is required for identity reads alongside each pid's stat entry
    Files.writeString(root.resolve("uptime"), "100000.00 200000.00\n");
  }

  private ProcFs procFs() {
    return new ProcFs(root);
  }

  private LinuxProcessManager manager(String projectRoot) {
    return new LinuxProcessManager(
        procFs(), projectRoot, "host-a", pid -> Optional.of(Instant.ofEpochSecond(pid)));
  }

  private LinuxProcessManager manager(
      String projectRoot, LinuxProcessManager.StartTimeResolver resolver) {
    return new LinuxProcessManager(procFs(), projectRoot, "host-a", resolver);
  }

  private void createPid(long pid, String exeTarget, String... cmdlineTokens) throws IOException {
    Path pidDir = Files.createDirectories(root.resolve(Long.toString(pid)));
    if (cmdlineTokens.length > 0) {
      byte[] bytes = String.join("\0", cmdlineTokens).getBytes(StandardCharsets.UTF_8);
      Files.write(pidDir.resolve("cmdline"), bytes);
    }
    if (exeTarget != null) {
      Files.createSymbolicLink(pidDir.resolve("exe"), Path.of(exeTarget));
    }
    Files.writeString(pidDir.resolve("stat"), statFor(pid));
  }

  private void createBootId(String bootId) throws IOException {
    Path randomDir =
        Files.createDirectories(root.resolve("sys").resolve("kernel").resolve("random"));
    Files.writeString(randomDir.resolve("boot_id"), bootId + "\n");
  }

  private static String statFor(long pid) {
    // state (field 3), ppid/pgrp (4-5), 16 fillers (6-21), then field 22 starttime
    return pid + " (proc) S 1 1 " + "0 ".repeat(16) + STAT_START_TICKS + " 0";
  }

  @Test
  void discoveryWithNoClientsReturnsDeterministicEmptyList() throws IOException {
    createBootId("boot-1");

    List<DiscoveredTarget> targets = manager("").discoverEligibleLocal();

    assertTrue(targets.isEmpty());
    assertEquals(targets, manager("").discoverEligibleLocal());
    TargetScanDiagnostics scan = manager("").lastScanDiagnostics();
    assertEquals(0, scan.unreadableEntries());
  }

  @Test
  void singleEligibleClientIsReportedWithNeutralIdentity() throws IOException {
    createBootId("boot-1");
    createPid(100L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");

    List<DiscoveredTarget> targets = manager("").discoverEligibleLocal();

    assertEquals(1, targets.size());
    DiscoveredTarget target = targets.get(0);
    assertEquals(ProcessOrigin.LOCAL, target.identity().origin());
    assertEquals(100L, target.identity().pid());
    assertEquals(Instant.ofEpochSecond(100L), target.identity().startTime());
    assertEquals("boot-1", target.identity().bootId());
    assertTrue(target.identity().isLocal());
    assertEquals("linux:100:" + STAT_START_TICKS, target.window().getId());
    assertEquals(100, target.window().getPid());
    assertNull(target.window().getHwnd());
    assertNull(target.window().getTitle());
    assertNull(target.window().getAccountName());
    assertNull(target.window().getBounds());
    assertEquals("process", target.source());
  }

  @Test
  void discoverySortsDeterministicallyByStartTimeThenPid() throws IOException {
    createBootId("boot-1");
    createPid(50L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");
    createPid(10L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");
    Map<Long, Instant> starts = Map.of(50L, T1, 10L, T2);
    LinuxProcessManager sorted = manager("", pid -> Optional.ofNullable(starts.get(pid)));

    List<DiscoveredTarget> targets = sorted.discoverEligibleLocal();
    List<DiscoveredTarget> rescan = sorted.discoverEligibleLocal();

    assertEquals(
        List.of(50L, 10L), targets.stream().map(target -> target.identity().pid()).toList());
    // identities are records and compare by value across scans; WindowInfo is identity-compared
    assertEquals(
        targets.stream().map(DiscoveredTarget::identity).toList(),
        rescan.stream().map(DiscoveredTarget::identity).toList());
  }

  @Test
  void gradleDaemonAndWorkerCommandLinesAreNeverEligible() throws IOException {
    createBootId("boot-1");
    createPid(
        200L,
        null,
        "java",
        "-cp",
        "gradle-daemon-main.jar",
        "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
        "runBuilderGoal");
    createPid(210L, null, "java", "gradle-worker", "runBuilderGoal", "/opt/micro6/client");

    assertTrue(manager("/opt/micro6").discoverEligibleLocal().isEmpty());
  }

  @Test
  void bashGradleWrapperCarryingAllMarkersIsExcludedByTheJavaExecutableRule() throws IOException {
    createBootId("boot-1");
    createPid(
        300L,
        null,
        "bash",
        "-lc",
        "./gradlew :client:runBuilderGoal --args=... net.runelite.client.RuneLite");

    assertTrue(manager("/opt/micro6").discoverEligibleLocal().isEmpty());
  }

  @Test
  void remoteLauncherAgentIsNeverEligible() throws IOException {
    createBootId("boot-1");
    createPid(400L, null, "python3", "/opt/micro6/scripts/remote-launcher/agent.py");

    assertTrue(manager("/opt/micro6").discoverEligibleLocal().isEmpty());
  }

  @Test
  void unrelatedJavaProcessIsNotEligible() throws IOException {
    createBootId("boot-1");
    createPid(500L, null, "java", "-jar", "otherapp.jar");

    assertTrue(manager("").discoverEligibleLocal().isEmpty());
  }

  @Test
  void theChromaScapeJvmItselfIsNeverEligible() throws IOException {
    createBootId("boot-1");
    long selfPid = ProcessHandle.current().pid();
    createPid(selfPid, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");

    assertTrue(manager("").discoverEligibleLocal().isEmpty());
  }

  @Test
  void runBuilderGoalWithoutProjectRootOrMainClassIsNotEligible() throws IOException {
    createBootId("boot-1");
    createPid(600L, null, "java", "runBuilderGoal", "--some-flag");

    assertTrue(manager("").discoverEligibleLocal().isEmpty());
  }

  @Test
  void runBuilderGoalWithProjectRootIsEligible() throws IOException {
    createBootId("boot-1");
    createPid(610L, null, "java", "runBuilderGoal", "--project", "/opt/micro6/client/build");

    assertEquals(1, manager("/opt/micro6").discoverEligibleLocal().size());
  }

  @Test
  void runBuilderGoalWithRuneLiteMainClassIsEligibleWithoutProjectRoot() throws IOException {
    createBootId("boot-1");
    createPid(700L, null, "java", "runBuilderGoal", "net.runelite.client.RuneLite");

    assertEquals(1, manager("").discoverEligibleLocal().size());
  }

  @Test
  void unreadableEntriesAreSkippedAndCountedWithoutAbortingTheScan() throws IOException {
    createBootId("boot-1");
    Files.createDirectories(root.resolve("800").resolve("cmdline"));
    createPid(810L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");
    // missing cmdline file
    Files.createDirectories(root.resolve("820"));
    // blank cmdline file
    Path blank = Files.createDirectories(root.resolve("830"));
    Files.writeString(blank.resolve("cmdline"), "");

    LinuxProcessManager scanning = manager("");
    List<DiscoveredTarget> targets = scanning.discoverEligibleLocal();

    assertEquals(1, targets.size());
    assertEquals(810L, targets.get(0).identity().pid());
    TargetScanDiagnostics scan = scanning.lastScanDiagnostics();
    assertEquals(3, scan.unreadableEntries());
    assertTrue(scan.diagnostics().contains(TargetSelectionDiagnostic.PROC_ENTRY_UNREADABLE));
    assertEquals(4, scan.scannedEntries());
  }

  @Test
  void exeSymlinkIsAuthoritativeOverArgv0() throws IOException {
    createBootId("boot-1");
    createPid(900L, "/opt/jre/bin/java", "client-runner", "net.runelite.client.RuneLite");
    createPid(910L, "/bin/bash", "java", "net.runelite.client.RuneLite");

    List<DiscoveredTarget> targets = manager("").discoverEligibleLocal();

    assertEquals(1, targets.size());
    assertEquals(900L, targets.get(0).identity().pid());
  }

  @Test
  void identityIsStableAcrossRepeatedResolvesOfTheSameProcess() throws IOException {
    createBootId("boot-1");
    createPid(100L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");
    LinuxProcessManager one = manager("");

    ProcessIdentity discovered = one.discoverEligibleLocal().get(0).identity();

    assertEquals(discovered, one.resolveLocal(100L).orElseThrow());
    assertEquals(discovered, manager("").discoverEligibleLocal().get(0).identity());
  }

  @Test
  void resolveLocalIsEmptyForGoneProcesses() {
    assertTrue(manager("").resolveLocal(424242L).isEmpty());
  }

  @Test
  void findLocalMatchesOnlyTheGivenIdentity() throws IOException {
    createBootId("boot-1");
    createPid(100L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");
    LinuxProcessManager one = manager("");

    ProcessIdentity discovered = one.discoverEligibleLocal().get(0).identity();
    ProcessIdentity other =
        new ProcessIdentity(
            ProcessOrigin.LOCAL, "host-a", 101L, Instant.ofEpochSecond(101L), "boot-1");

    assertTrue(one.findLocal(discovered).isPresent());
    assertTrue(one.findLocal(other).isEmpty());
    assertTrue(one.findLocal(null).isEmpty());
  }

  @Test
  void bootIdIsEmptyWhenTheHostDoesNotExposeIt() throws IOException {
    createPid(100L, null, "java", "-cp", "app.jar", "net.runelite.client.RuneLite");

    DiscoveredTarget target = manager("").discoverEligibleLocal().get(0);

    assertEquals("", target.identity().bootId());
    assertNotNull(target.identity().startTime());
  }
}
