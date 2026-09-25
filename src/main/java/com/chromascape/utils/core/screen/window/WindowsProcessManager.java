package com.chromascape.utils.core.screen.window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows discovery: wraps the existing {@link WindowHandler} enumeration, preserving its
 * canvas-preference ranking, sequential ids, auto-detection behavior and candidate flags, and
 * enriches every {@link WindowInfo} with a neutral {@link ProcessIdentity} built from the JDK
 * process start time.
 *
 * <p>Windows has no procfs boot id, so identities carry an empty boot id; pid-reuse detection on
 * Windows relies on the JDK start time. When no start time is observable for a candidate, the
 * candidate is still offered for UI compatibility but carries no identity: nothing is invented, the
 * candidate is reported through the {@link TargetSelectionDiagnostic#IDENTITY_UNAVAILABLE}
 * diagnostic, and attachment-oriented resolution fails closed instead of matching by pid.
 */
public class WindowsProcessManager implements ProcessManager {

  private static final Logger logger = LoggerFactory.getLogger(WindowsProcessManager.class);

  private final String hostId;
  private volatile TargetScanDiagnostics lastScan = TargetScanDiagnostics.EMPTY;

  /**
   * Creates a Windows process manager.
   *
   * @param hostId local host identity recorded on discovered identities; may be empty
   */
  public WindowsProcessManager(String hostId) {
    this.hostId = hostId == null ? "" : hostId;
  }

  @Override
  public List<DiscoveredTarget> discoverEligibleLocal() {
    List<DiscoveredTarget> targets = new ArrayList<>();
    List<TargetSelectionDiagnostic> diagnostics = new ArrayList<>();
    for (WindowHandler.WindowCandidate candidate : WindowHandler.getUiWindowCandidates()) {
      WindowInfo window = candidate.window();
      ProcessIdentity identity = buildIdentity(window.getPid());
      WindowInfo enriched = identity != null ? window.withIdentity(identity) : window;
      targets.add(
          new DiscoveredTarget(
              identity,
              enriched,
              candidate.source(),
              candidate.fallback(),
              candidate.captureable(),
              candidate.selectionReason()));
      if (identity == null) {
        diagnostics.add(TargetSelectionDiagnostic.IDENTITY_UNAVAILABLE);
        logger.warn(
            "No process start time observable for pid {}; candidate {} is offered without an identity and fails closed on resolution",
            window.getPid(),
            window.getId());
      }
    }
    lastScan = new TargetScanDiagnostics(targets.size(), 0, diagnostics);
    return targets;
  }

  @Override
  public Optional<ProcessIdentity> resolveLocal(long pid) {
    return Optional.ofNullable(buildIdentity(pid));
  }

  @Override
  public TargetScanDiagnostics lastScanDiagnostics() {
    return lastScan;
  }

  private ProcessIdentity buildIdentity(long pid) {
    if (pid <= 0) {
      return null;
    }
    Optional<Instant> startTime =
        ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant());
    return startTime
        .map(instant -> new ProcessIdentity(ProcessOrigin.LOCAL, hostId, pid, instant, ""))
        .orElse(null);
  }
}
