package com.chromascape.utils.core.screen.window;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Single read path from the HTTP controllers to process discovery and selection.
 *
 * <p>On Windows it delegates to the existing {@link WindowHandler} enumeration exactly as before,
 * preserving the JSON shape, sequential ids and the canvas-preference ranking. On Linux it builds
 * candidates from {@link ProcessManager#discoverEligibleLocal()} with neutral identities and no
 * window handle, and resolves targets fail-closed: multiple eligible clients without an explicit
 * matching selection are refused with {@link TargetSelectionDiagnostic#AMBIGUOUS_TARGET} instead of
 * a first-match pick.
 */
@Service
public class TargetDiscoveryService {

  private final ProcessManagerFactory processManagerFactory;

  /** Selection service; null only via the convenience constructor used where selection is unused. */
  private final WindowSelectionService windowSelectionService;

  /**
   * Spring constructor.
   *
   * @param processManagerFactory platform switch for discovery
   * @param windowSelectionService current selection used by fail-closed resolution
   */
  @Autowired
  public TargetDiscoveryService(
      ProcessManagerFactory processManagerFactory, WindowSelectionService windowSelectionService) {
    this.processManagerFactory = processManagerFactory;
    this.windowSelectionService = windowSelectionService;
  }

  /**
   * Convenience constructor for callers that only enumerate candidates; selection resolution is
   * unavailable and returns the strict candidate-only rule.
   *
   * @param processManagerFactory platform switch for discovery
   */
  public TargetDiscoveryService(ProcessManagerFactory processManagerFactory) {
    this(processManagerFactory, null);
  }

  /**
   * Returns the debugger/UI candidates for the current platform, preserving the Windows shape and
   * exposing neutral Linux candidates with {@code source} {@code "process"} and {@code fallback}
   * {@code false} ({@code captureable} is decided by SC-1665 and is {@code false} here).
   */
  public List<WindowHandler.WindowCandidate> listUiCandidates() {
    ProcessManagerFactory.Platform platform = ProcessManagerFactory.currentPlatform();
    if (platform == ProcessManagerFactory.Platform.WINDOWS) {
      return WindowHandler.getUiWindowCandidates();
    }
    if (platform == ProcessManagerFactory.Platform.LINUX) {
      return processManager().discoverEligibleLocal().stream()
          .map(
              target ->
                  new WindowHandler.WindowCandidate(
                      target.window(),
                      false,
                      target.source(),
                      false,
                      target.selectionReason()))
          .toList();
    }
    return List.of();
  }

  /**
   * Returns all detected target windows for the debug endpoints: exactly
   * {@link WindowHandler#getAllTargetWindows()} on Windows, neutral discovered windows on Linux.
   */
  public List<WindowInfo> listAllTargetWindows() {
    ProcessManagerFactory.Platform platform = ProcessManagerFactory.currentPlatform();
    if (platform == ProcessManagerFactory.Platform.WINDOWS) {
      return WindowHandler.getAllTargetWindows();
    }
    if (platform == ProcessManagerFactory.Platform.LINUX) {
      return processManager().discoverEligibleLocal().stream()
          .map(DiscoveredTarget::window)
          .toList();
    }
    return List.of();
  }

  /**
   * Fail-closed resolution of the target a consumer may act on.
   *
   * <p>Refuses to return a target unless exactly one candidate exists or an explicit selection
   * matches one identity. An explicit selection matching no current candidate is refused with
   * {@link TargetSelectionDiagnostic#STALE_SELECTION}; multiple candidates without a matching
   * selection are refused with {@link TargetSelectionDiagnostic#AMBIGUOUS_TARGET}.
   *
   * @return the candidates, the resolved target (when any) and the refusal diagnostic (null when
   *     resolved or empty-for-supported-reason)
   */
  public TargetResolution resolveSelectedTarget() {
    ProcessManager processManager = processManager();
    if (!processManager.isPlatformSupported()) {
      return new TargetResolution(
          List.of(), Optional.empty(), TargetSelectionDiagnostic.UNSUPPORTED_PLATFORM);
    }
    List<DiscoveredTarget> candidates = processManager.discoverEligibleLocal();
    if (candidates.isEmpty()) {
      return new TargetResolution(
          candidates, Optional.empty(), TargetSelectionDiagnostic.NO_ELIGIBLE_TARGET);
    }
    if (windowSelectionService != null && windowSelectionService.hasSelection()) {
      ProcessIdentity selectedIdentity = windowSelectionService.getSelectedIdentity();
      Optional<DiscoveredTarget> match =
          selectedIdentity == null
              ? Optional.empty()
              : candidates.stream()
                  .filter(target -> selectedIdentity.equals(target.identity()))
                  .findFirst();
      if (match.isEmpty()) {
        return new TargetResolution(
            candidates, Optional.empty(), TargetSelectionDiagnostic.STALE_SELECTION);
      }
      return new TargetResolution(candidates, match, null);
    }
    if (candidates.size() > 1) {
      return new TargetResolution(
          candidates, Optional.empty(), TargetSelectionDiagnostic.AMBIGUOUS_TARGET);
    }
    return new TargetResolution(candidates, Optional.of(candidates.get(0)), null);
  }

  private ProcessManager processManager() {
    return processManagerFactory.getProcessManager();
  }

  /**
   * Outcome of fail-closed resolution.
   *
   * @param candidates all candidates of the scan
   * @param target the resolved target, empty on refusal
   * @param diagnostic the refusal diagnostic; null when a target was resolved
   */
  public record TargetResolution(
      List<DiscoveredTarget> candidates,
      Optional<DiscoveredTarget> target,
      TargetSelectionDiagnostic diagnostic) {

    public TargetResolution {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      target = target == null ? Optional.empty() : target;
    }

    /** Whether a target was resolved. */
    public boolean isResolved() {
      return target.isPresent();
    }
  }
}
