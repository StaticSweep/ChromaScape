package com.chromascape.utils.core.screen.window;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Single read path from the HTTP controllers to process discovery and selection.
 *
 * <p>Every listing and resolution flows through {@link ProcessManagerFactory#getProcessManager()} —
 * the single platform switch — so the identities presented by {@link #listUiCandidates()} are
 * exactly the identities observed by {@link #resolveSelectedTarget()}: a Windows selection made
 * from the debugger UI therefore resolves instead of degrading to {@code STALE_SELECTION}. On
 * Windows the discovery implementation wraps the existing {@link WindowHandler} enumeration,
 * preserving the JSON shape, sequential ids, the canvas-preference ranking, deduplication and the
 * {@code source}/{@code fallback}/{@code captureable} flags, and enriches each candidate with a
 * neutral identity. On Linux it builds candidates from {@link
 * ProcessManager#discoverEligibleLocal()} with neutral identities and no window handle, and
 * resolves targets fail-closed: multiple eligible clients without an explicit matching selection
 * are refused with {@link TargetSelectionDiagnostic#AMBIGUOUS_TARGET} instead of a first-match
 * pick, and a selection without a matching identity is refused with {@link
 * TargetSelectionDiagnostic#STALE_SELECTION} rather than resolved by pid.
 *
 * <p>Compatibility note: {@link #listUiCandidates()} returns {@link WindowHandler.WindowCandidate},
 * the Windows-owned nested record, so the UI candidate shape stays byte-identical. Instantiating
 * that nested record never initializes {@link WindowHandler} (whose static state would load the
 * Win32 user32 library); the Linux runtime isolation test executes this facade with an empty {@code
 * jna.library.path} to prove it.
 */
@Service
public class TargetDiscoveryService {

  private final ProcessManagerFactory processManagerFactory;

  /**
   * Selection service; null only via the convenience constructor used where selection is unused.
   */
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
   * Returns the debugger/UI candidates for the current platform through the single discovery path.
   *
   * <p>The {@code WindowCandidate} flags ({@code source}, {@code fallback}, {@code captureable},
   * {@code selectionReason}) pass through from discovery unchanged: Windows keeps its {@code
   * "supported-client"}/{@code "java-fallback"} metadata, Linux exposes {@code "process"}
   * candidates with {@code fallback} and {@code captureable} false ({@code captureable} is decided
   * by SC-1665). Unsupported platforms yield an empty list.
   */
  public List<WindowHandler.WindowCandidate> listUiCandidates() {
    return processManager().discoverEligibleLocal().stream()
        .map(
            target ->
                new WindowHandler.WindowCandidate(
                    target.window(),
                    target.fallback(),
                    target.source(),
                    target.captureable(),
                    target.selectionReason()))
        .toList();
  }

  /**
   * Returns all detected target windows for the debug endpoints through the single discovery path:
   * the Windows discovery-preferred candidates (one per process, identities attached) and the
   * neutral discovered windows on Linux; an empty list on unsupported platforms.
   */
  public List<WindowInfo> listAllTargetWindows() {
    return processManager().discoverEligibleLocal().stream().map(DiscoveredTarget::window).toList();
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
