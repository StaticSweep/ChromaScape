package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelectionValidationTest {

  private static final Instant START = Instant.ofEpochMilli(5_000L);

  /** Minimal stub exposing a configurable local process table. */
  private static final class StubProcessManager implements ProcessManager {

    private final Map<Long, ProcessIdentity> processes = new HashMap<>();
    private int resolveCalls;

    void add(ProcessIdentity identity) {
      processes.put(identity.pid(), identity);
    }

    int resolveCalls() {
      return resolveCalls;
    }

    @Override
    public List<DiscoveredTarget> discoverEligibleLocal() {
      return List.of();
    }

    @Override
    public Optional<ProcessIdentity> resolveLocal(long pid) {
      resolveCalls++;
      return Optional.ofNullable(processes.get(pid));
    }
  }

  private static WindowInfo windowWith(ProcessIdentity identity) {
    return new WindowInfo("1", null, null, null, (int) identity.pid(), false, null, identity);
  }

  @Test
  void noSelectionValidatesToNone() {
    assertEquals(
        SelectionValidation.NONE,
        new WindowSelectionService().validateSelected(new StubProcessManager()));
  }

  @Test
  void validSelectionRevalidatesAgainstLiveIdentity() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    processManager.add(identity);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(identity));

    assertEquals(SelectionValidation.VALID, service.validateSelected(processManager));
  }

  @Test
  void exitedProcessValidatesToExited() {
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(
        windowWith(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1")));

    assertEquals(SelectionValidation.EXITED, service.validateSelected(new StubProcessManager()));
  }

  @Test
  void reusedPidValidatesToReplacedByStartTimeMismatch() {
    StubProcessManager processManager = new StubProcessManager();
    processManager.add(
        new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START.plusSeconds(10), "boot-1"));
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(
        windowWith(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1")));

    assertEquals(SelectionValidation.REPLACED, service.validateSelected(processManager));
  }

  @Test
  void reusedPidValidatesToReplacedByBootIdMismatch() {
    StubProcessManager processManager = new StubProcessManager();
    processManager.add(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-2"));
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(
        windowWith(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1")));

    assertEquals(SelectionValidation.REPLACED, service.validateSelected(processManager));
  }

  @Test
  void remoteIdentityIsRefusedBeforeLocalResolution() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity remote = new ProcessIdentity(ProcessOrigin.REMOTE, "", 10L, START, "boot-1");
    processManager.add(remote);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(remote));

    assertEquals(SelectionValidation.NOT_LOCAL, service.validateSelected(processManager));
  }

  @Test
  void staleGenerationIsDetectedBeforeResolution() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity first = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    ProcessIdentity second = new ProcessIdentity(ProcessOrigin.LOCAL, "", 11L, START, "boot-1");
    processManager.add(second);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(first));
    service.setSelectedWindow(windowWith(second));

    assertEquals(
        SelectionValidation.STALE_GENERATION, service.validateSelected(processManager, 1L));
    assertEquals(SelectionValidation.VALID, service.validateSelected(processManager, 2L));
  }

  @Test
  void exactCurrentGenerationIsAccepted() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    processManager.add(identity);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(identity));

    assertEquals(
        SelectionValidation.VALID,
        service.validateSelected(processManager, service.getSelectedGeneration()));
  }

  @Test
  void futureGenerationIsRejected() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    processManager.add(identity);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(identity));

    // The live identity matches, but the caller observed a generation the selection never had:
    // exact fencing must refuse it instead of validating
    assertEquals(
        SelectionValidation.STALE_GENERATION,
        service.validateSelected(processManager, service.getSelectedGeneration() + 1L));
  }

  @Test
  void clearAfterSelectionValidatesToNone() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    processManager.add(identity);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(identity));
    long captured = service.getSelectedGeneration();
    service.clearSelection();

    assertEquals(SelectionValidation.NONE, service.validateSelected(processManager, captured));
  }

  @Test
  void noProcessResolutionOccursAfterAGenerationMismatch() {
    StubProcessManager processManager = new StubProcessManager();
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    processManager.add(identity);
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(windowWith(identity));

    service.validateSelected(processManager, service.getSelectedGeneration() - 1L);
    service.validateSelected(processManager, service.getSelectedGeneration() + 1L);

    assertEquals(0, processManager.resolveCalls());
  }

  @Test
  void legacySelectionWithoutIdentityFallsBackToPidLiveness() {
    ProcessIdentity live = new ProcessIdentity(ProcessOrigin.LOCAL, "", 77L, START, "");
    StubProcessManager withProcess = new StubProcessManager();
    withProcess.add(live);
    StubProcessManager withoutProcess = new StubProcessManager();

    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(new WindowInfo("1", null, "title", null, 77, true, null));

    assertEquals(SelectionValidation.VALID, service.validateSelected(withProcess));
    assertEquals(SelectionValidation.EXITED, service.validateSelected(withoutProcess));
  }

  @Test
  void nullManagerValidatesToUnsupportedPlatform() {
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(
        windowWith(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1")));

    assertEquals(SelectionValidation.UNSUPPORTED_PLATFORM, service.validateSelected(null));
  }

  @Test
  void unsupportedPlatformManagerValidatesToUnsupportedPlatform() {
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(
        windowWith(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1")));

    assertEquals(
        SelectionValidation.UNSUPPORTED_PLATFORM,
        service.validateSelected(new UnsupportedProcessManager()));
  }
}
