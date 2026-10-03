package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WindowControllerTest {

  private static final long STAT_START_TICKS = 12345L;

  @TempDir Path root;

  /**
   * A discovery service over a synthetic-procfs Linux factory, so selection round-trips can be
   * exercised without any platform window system.
   */
  private TargetDiscoveryService linuxDiscovery(WindowSelectionService selection)
      throws IOException {
    Files.writeString(root.resolve("uptime"), "100000.00 200000.00\n");
    ProcFs procFs = new ProcFs(root);
    ProcessManagerFactory factory =
        new ProcessManagerFactory(procFs, "", "") {
          @Override
          public ProcessManager getProcessManager() {
            return new LinuxProcessManager(
                procFs, "", "host-a", pid -> Optional.of(Instant.ofEpochSecond(pid)));
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
        pidDir.resolve("stat"), pid + " (proc) S 1 1 " + "0 ".repeat(16) + STAT_START_TICKS + " 0");
  }

  @Test
  void getCurrentWindowReturnsNoContentWhenNoWindowIsSelected() {
    WindowController controller = new WindowController(new WindowSelectionService());

    ResponseEntity<Map<String, Object>> response = controller.getCurrentWindow();

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
  }

  @Test
  void getCurrentWindowReturnsSelectedWindowPayload() {
    WindowSelectionService selectionService = new WindowSelectionService();
    selectionService.setSelectedWindow(
        new WindowInfo(
            "7", null, "RuneLite - test", "test", 1234, true, new Rectangle(10, 20, 300, 400)));
    WindowController controller = new WindowController(selectionService);

    ResponseEntity<Map<String, Object>> response = controller.getCurrentWindow();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("7", response.getBody().get("id"));
    assertEquals("RuneLite - test", response.getBody().get("title"));
  }

  @Test
  void selectingNeutralLinuxCandidateReturnsOkWithNullTitleAndStoresIdentity() throws IOException {
    createEligiblePid(100L);
    WindowSelectionService selection = new WindowSelectionService();
    TargetDiscoveryService discovery = linuxDiscovery(selection);
    WindowController controller = new WindowController(selection, discovery);

    ResponseEntity<Map<String, String>> response =
        controller.selectWindow(Map.of("id", "linux:100:" + STAT_START_TICKS));

    // The selection is valid: a null title must not surface as HTTP 500
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("selected", response.getBody().get("status"));
    assertEquals("linux:100:" + STAT_START_TICKS, response.getBody().get("id"));
    assertTrue(response.getBody().containsKey("title"));
    assertNull(response.getBody().get("title"));

    // The candidate's neutral identity was stored, so resolution observes the same identity
    assertTrue(selection.hasSelection());
    ProcessIdentity stored = selection.getSelectedIdentity();
    assertNotNull(stored);
    assertEquals(100L, stored.pid());
    assertEquals(Instant.ofEpochSecond(100L), stored.startTime());

    TargetDiscoveryService.TargetResolution resolution = discovery.resolveSelectedTarget();
    assertTrue(resolution.isResolved());
    assertEquals(stored, resolution.target().orElseThrow().identity());
  }

  @Test
  void selectingTitledCandidateKeepsWindowsResponseContent() {
    // Windows-shaped candidates carry a title; the response contract (status/id/title) is unchanged
    WindowSelectionService selection = new WindowSelectionService();
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, "", 1234L, Instant.ofEpochMilli(42L), "");
    WindowInfo titled =
        new WindowInfo(
                "1", null, "RuneLite - test", "test", 1234, true, new Rectangle(0, 0, 600, 400))
            .withIdentity(identity);
    ProcessManagerFactory factory =
        new ProcessManagerFactory(ProcFs.production(), "", "") {
          @Override
          public ProcessManager getProcessManager() {
            return new StubSingleCandidateManager(titled);
          }
        };
    WindowController controller =
        new WindowController(selection, new TargetDiscoveryService(factory, selection));

    ResponseEntity<Map<String, String>> response = controller.selectWindow(Map.of("id", "1"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("selected", response.getBody().get("status"));
    assertEquals("1", response.getBody().get("id"));
    assertEquals("RuneLite - test", response.getBody().get("title"));
    assertEquals(identity, selection.getSelectedIdentity());
  }

  /** Stub manager serving one fixed candidate, used to exercise titled (Windows-shaped) selects. */
  private static final class StubSingleCandidateManager implements ProcessManager {

    private final WindowInfo window;

    StubSingleCandidateManager(WindowInfo window) {
      this.window = window;
    }

    @Override
    public List<DiscoveredTarget> discoverEligibleLocal() {
      return List.of(
          new DiscoveredTarget(
              window.getProcessIdentity(), window, "supported-client", false, true, "reason"));
    }

    @Override
    public Optional<ProcessIdentity> resolveLocal(long pid) {
      return window.getProcessIdentity() != null && window.getProcessIdentity().pid() == pid
          ? Optional.of(window.getProcessIdentity())
          : Optional.empty();
    }
  }
}
