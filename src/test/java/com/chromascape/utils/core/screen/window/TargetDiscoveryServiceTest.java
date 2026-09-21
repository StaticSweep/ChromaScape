package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TargetDiscoveryServiceTest {

  private static final long STAT_START_TICKS = 12345L;

  @TempDir Path root;

  private final WindowSelectionService selection = new WindowSelectionService();

  @BeforeEach
  void createSyntheticHostFiles() throws IOException {
    // /proc/uptime is required for identity reads alongside each pid's stat entry
    Files.writeString(root.resolve("uptime"), "100000.00 200000.00\n");
  }

  private TargetDiscoveryService service(String projectRoot) {
    ProcFs procFs = new ProcFs(root);
    ProcessManagerFactory factory =
        new ProcessManagerFactory(procFs, projectRoot, "host-a") {
          @Override
          public ProcessManager getProcessManager() {
            return new LinuxProcessManager(
                procFs, projectRoot, "host-a", pid -> Optional.of(Instant.ofEpochSecond(pid)));
          }
        };
    return new TargetDiscoveryService(factory, selection);
  }

  private TargetDiscoveryService unsupportedService() {
    ProcessManagerFactory factory =
        new ProcessManagerFactory(ProcFs.production(), "", "") {
          @Override
          public ProcessManager getProcessManager() {
            return new UnsupportedProcessManager();
          }
        };
    return new TargetDiscoveryService(factory, selection);
  }

  private void createEligiblePid(long pid) throws IOException {
    Path pidDir = Files.createDirectories(root.resolve(Long.toString(pid)));
    Files.write(
        pidDir.resolve("cmdline"),
        String.join("\0", "java", "-cp", "app.jar", "net.runelite.client.RuneLite")
            .getBytes(StandardCharsets.UTF_8));
    Files.writeString(
        pidDir.resolve("stat"),
        // state (3), ppid/pgrp (4-5), 16 fillers (6-21), field 22 starttime
        pid + " (proc) S 1 1 " + "0 ".repeat(16) + STAT_START_TICKS + " 0");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void linuxCandidatesCarryTheNeutralShapeWithoutWindowHandles() throws IOException {
    createEligiblePid(100L);

    List<WindowHandler.WindowCandidate> candidates = service("").listUiCandidates();

    assertEquals(1, candidates.size());
    WindowInfo window = candidates.get(0).window();
    assertEquals("linux:100:" + STAT_START_TICKS, window.getId());
    assertEquals(100, window.getPid());
    assertNull(window.getHwnd());
    assertNull(window.getTitle());
    assertNull(window.getAccountName());
    assertNull(window.getBounds());
    assertEquals("process", candidates.get(0).source());
    assertFalse(candidates.get(0).fallback());
    assertFalse(candidates.get(0).captureable());
  }

  @Test
  void resolutionRefusesMultipleCandidatesWithoutSelection() throws IOException {
    createEligiblePid(100L);
    createEligiblePid(200L);

    TargetDiscoveryService.TargetResolution resolution = service("").resolveSelectedTarget();

    assertFalse(resolution.isResolved());
    assertEquals(2, resolution.candidates().size());
    assertEquals(TargetSelectionDiagnostic.AMBIGUOUS_TARGET, resolution.diagnostic());
  }

  @Test
  void resolutionReturnsOneCandidate() throws IOException {
    createEligiblePid(100L);

    TargetDiscoveryService.TargetResolution resolution = service("").resolveSelectedTarget();

    assertTrue(resolution.isResolved());
    assertNull(resolution.diagnostic());
    assertEquals(100L, resolution.target().orElseThrow().identity().pid());
  }

  @Test
  void resolutionHonorsAnExplicitSelectionAmongMultipleCandidates() throws IOException {
    createEligiblePid(100L);
    createEligiblePid(200L);
    ProcessIdentity selected =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 200L, Instant.ofEpochSecond(200L), "");
    selection.setSelectedWindow(
        new WindowInfo(
            "linux:200:" + STAT_START_TICKS, null, null, null, 200, false, null, selected));

    TargetDiscoveryService.TargetResolution resolution = service("").resolveSelectedTarget();

    assertTrue(resolution.isResolved());
    assertNull(resolution.diagnostic());
    assertEquals(200L, resolution.target().orElseThrow().identity().pid());
  }

  @Test
  void resolutionRefusesSelectionThatMatchesNoCandidate() throws IOException {
    createEligiblePid(100L);
    selection.setSelectedWindow(
        new WindowInfo(
            "linux:999:" + STAT_START_TICKS,
            null,
            null,
            null,
            999,
            false,
            null,
            new ProcessIdentity(
                ProcessOrigin.LOCAL, "host-a", 999L, Instant.ofEpochSecond(999L), "")));

    TargetDiscoveryService.TargetResolution resolution = service("").resolveSelectedTarget();

    assertFalse(resolution.isResolved());
    assertEquals(TargetSelectionDiagnostic.STALE_SELECTION, resolution.diagnostic());
  }

  @Test
  void resolutionReportsNoEligibleTargetWhenNothingMatches() {
    TargetDiscoveryService.TargetResolution resolution = service("").resolveSelectedTarget();

    assertFalse(resolution.isResolved());
    assertEquals(TargetSelectionDiagnostic.NO_ELIGIBLE_TARGET, resolution.diagnostic());
    assertTrue(resolution.candidates().isEmpty());
  }

  @Test
  void resolutionReportsUnsupportedPlatforms() {
    TargetDiscoveryService.TargetResolution resolution =
        unsupportedService().resolveSelectedTarget();

    assertFalse(resolution.isResolved());
    assertEquals(TargetSelectionDiagnostic.UNSUPPORTED_PLATFORM, resolution.diagnostic());
  }

  @Test
  void unsupportedPlatformListingsAreEmpty() {
    TargetDiscoveryService unsupported = unsupportedService();

    assertTrue(unsupported.listUiCandidates().isEmpty());
    assertTrue(unsupported.listAllTargetWindows().isEmpty());
  }

  @Test
  void windowsCandidateIdentitySurvivesEnumerationSelectionAndResolution() {
    // Windows-shaped discovery: supported-client and fallback candidates carrying identities
    ProcessIdentity first =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 4242L, Instant.ofEpochMilli(777L), "");
    WindowInfo firstWindow =
        new WindowInfo(
            "1",
            null,
            "RuneLite - alpha",
            "alpha",
            4242,
            true,
            new Rectangle(0, 0, 600, 400),
            first);
    DiscoveredTarget firstTarget =
        new DiscoveredTarget(
            first, firstWindow, "supported-client", false, true, "direct capture canvas available");
    ProcessIdentity second =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host-a", 4243L, Instant.ofEpochMilli(999L), "");
    WindowInfo secondWindow =
        new WindowInfo(
            "2", null, "Some Java Window", null, 4243, true, new Rectangle(5, 5, 640, 480), second);
    DiscoveredTarget secondTarget =
        new DiscoveredTarget(
            second, secondWindow, "java-fallback", true, true, "fallback standalone Java window");
    TargetDiscoveryService service =
        stubService(new StubCandidateManager(List.of(firstTarget, secondTarget)));

    List<WindowHandler.WindowCandidate> candidates = service.listUiCandidates();

    // Enumeration: same order, same metadata, identities attached
    assertEquals(2, candidates.size());
    assertEquals(List.of("1", "2"), candidates.stream().map(c -> c.window().getId()).toList());
    assertEquals("supported-client", candidates.get(0).source());
    assertFalse(candidates.get(0).fallback());
    assertTrue(candidates.get(0).captureable());
    assertEquals(first, candidates.get(0).window().getProcessIdentity());
    assertEquals("java-fallback", candidates.get(1).source());
    assertTrue(candidates.get(1).fallback());
    assertEquals(second, candidates.get(1).window().getProcessIdentity());
    assertEquals(List.of(firstWindow, secondWindow), service.listAllTargetWindows());

    // The controller stores exactly the presented candidate window
    selection.setSelectedWindow(candidates.get(0).window());

    // Resolution observes the same identity that was presented and stored
    TargetDiscoveryService.TargetResolution resolution = service.resolveSelectedTarget();
    assertTrue(resolution.isResolved());
    assertNull(resolution.diagnostic());
    assertEquals(first, resolution.target().orElseThrow().identity());
    assertEquals(4242L, resolution.target().orElseThrow().identity().pid());
    assertEquals(
        Instant.ofEpochMilli(777L), resolution.target().orElseThrow().identity().startTime());
  }

  @Test
  void legacySelectionWithoutIdentityFailsClosedInsteadOfMatchingByPid() throws IOException {
    createEligiblePid(100L);
    // A pre-enrichment selection carrying only a pid: even though the enriched candidate has the
    // same pid, resolution must refuse it instead of silently selecting by pid
    selection.setSelectedWindow(
        new WindowInfo("linux:100:" + STAT_START_TICKS, null, null, null, 100, false, null));

    TargetDiscoveryService.TargetResolution resolution = service("").resolveSelectedTarget();

    assertFalse(resolution.isResolved());
    assertEquals(TargetSelectionDiagnostic.STALE_SELECTION, resolution.diagnostic());
  }

  /** Stub manager serving a fixed candidate list, used to exercise the facade contract. */
  private static final class StubCandidateManager implements ProcessManager {

    private final List<DiscoveredTarget> targets;

    StubCandidateManager(List<DiscoveredTarget> targets) {
      this.targets = targets;
    }

    @Override
    public List<DiscoveredTarget> discoverEligibleLocal() {
      return targets;
    }

    @Override
    public Optional<ProcessIdentity> resolveLocal(long pid) {
      return targets.stream()
          .filter(target -> target.identity() != null && target.identity().pid() == pid)
          .findFirst()
          .map(DiscoveredTarget::identity);
    }
  }

  private TargetDiscoveryService stubService(ProcessManager manager) {
    ProcessManagerFactory factory =
        new ProcessManagerFactory(ProcFs.production(), "", "") {
          @Override
          public ProcessManager getProcessManager() {
            return manager;
          }
        };
    return new TargetDiscoveryService(factory, selection);
  }
}
